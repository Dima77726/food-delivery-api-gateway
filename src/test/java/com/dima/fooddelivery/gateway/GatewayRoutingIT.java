package com.dima.fooddelivery.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шлюз против настоящих HTTP-серверов, изображающих монолит, каталог и сервис уведомлений.
 *
 * <p>Серверы — встроенный в JDK {@code HttpServer}, а не моки: проверяется то, что уходит
 * по сети, — путь, заголовки, — а мок проверил бы только вызовы внутри шлюза. Отдельная
 * библиотека вроде WireMock ради двух эндпоинтов не нужна.
 *
 * <p>Каждый сервер запоминает заголовки последнего запроса: по ним видно, что шлюз
 * добавил, а что срезал.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class GatewayRoutingIT {

    private static final Backend CORE = Backend.start("core");
    private static final Backend NOTIFICATIONS = Backend.start("notification-service");
    private static final Backend CATALOG = Backend.start("catalog-service");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        registry.add("CORE_SERVICE_URL", CORE::url);
        registry.add("NOTIFICATION_SERVICE_URL", NOTIFICATIONS::url);
        registry.add("CATALOG_SERVICE_URL", CATALOG::url);
    }

    @AfterAll
    static void stopBackends() {
        CORE.server.stop(0);
        NOTIFICATIONS.server.stop(0);
        CATALOG.server.stop(0);
    }

    @BeforeEach
    void resetBackends() {
        CORE.lastHeaders.clear();
        NOTIFICATIONS.lastHeaders.clear();
        CATALOG.lastHeaders.clear();
    }

    @Test
    void shouldRouteNotificationsToNotificationService() throws Exception {
        HttpResponse<String> response = send("/api/v1/notifications/me", Map.of());

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertEquals("notification-service:/api/v1/notifications/me", response.body()),
                () -> assertTrue(CORE.lastHeaders.isEmpty(), "монолит не должен был получить запрос")
        );
    }

    /** Всё, что не вынесено, по-прежнему уходит в монолит — по тому же пути. */
    @Test
    void shouldRouteEverythingElseToCore() throws Exception {
        HttpResponse<String> response = send("/api/v1/customers/7/orders?size=5", Map.of());

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertEquals("core:/api/v1/customers/7/orders?size=5", response.body()),
                () -> assertTrue(NOTIFICATIONS.lastHeaders.isEmpty()),
                () -> assertTrue(CATALOG.lastHeaders.isEmpty())
        );
    }

    /** Рестораны, меню и поиск ушли в каталог; клиент об этом не знает — пути прежние. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/restaurants",
            "/api/v1/restaurants/5",
            "/api/v1/restaurants/5/menu",
            "/api/v1/restaurants/5/menu/manage",
            "/api/v1/search?q=pizza",
            "/api/v1/admin/search/reindex"
    })
    void shouldRouteCatalogPathsToCatalog(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("catalog-service:" + path, response.body());
    }

    /**
     * Под {@code /restaurants/{id}} живут и чужие для каталога данные: заказы ресторана,
     * отзывы, аналитика. Они обязаны остаться у монолита, хотя путь начинается с ресторана.
     * Ошибись здесь порядок маршрутов — и владелец ресторана перестал бы видеть свои заказы.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/restaurants/5/orders",
            "/api/v1/restaurants/5/orders/feed",
            "/api/v1/restaurants/5/orders/9/accept",
            "/api/v1/restaurants/5/orders/9/deliveries",
            "/api/v1/restaurants/5/reviews",
            "/api/v1/restaurants/5/reviews/summary",
            "/api/v1/restaurants/5/analytics/sales"
    })
    void shouldKeepRestaurantScopedMonolithPathsOnCore(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("core:" + path, response.body());
    }

    @Test
    void shouldGenerateCorrelationIdAndPassItToService() throws Exception {
        HttpResponse<String> response = send("/api/v1/ping", Map.of());

        String returned = response.headers().firstValue(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER).orElse(null);
        List<String> returnedAll = response.headers().allValues(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);

        assertAll(
                () -> assertNotNull(returned, "клиент обязан получить метку"),
                () -> assertEquals(1, returnedAll.size(), "метка не должна дублироваться в ответе"),
                () -> assertEquals(returned, CORE.header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER),
                        "сервис обязан получить ту же метку, что вернулась клиенту")
        );
    }

    /**
     * Метка клиента сохраняется, но мусор из неё вычищается: перевод строки в метке —
     * это подделанная строка лога в каждом сервисе, куда она доедет.
     */
    @Test
    void shouldSanitizeCorrelationIdSuppliedByClient() throws Exception {
        send("/api/v1/ping", Map.of(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, "abc-123 <script>"));

        assertEquals("abc-123script", CORE.header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER));
    }

    /** Если эта проверка упадёт, клиент сможет назваться чужим пользователем перед сервисами. */
    @Test
    void shouldStripUserIdHeaderSuppliedByClient() throws Exception {
        send("/api/v1/notifications/me", Map.of("X-User-Id", "42"));

        assertAll(
                () -> assertFalse(NOTIFICATIONS.lastHeaders.isEmpty(), "запрос должен был дойти до сервиса"),
                () -> assertNull(NOTIFICATIONS.header("X-User-Id"), "заголовок клиента не должен доходить до сервиса")
        );
    }

    private HttpResponse<String> send(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path)).GET();
        headers.forEach(request::header);

        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Сервер-заглушка. Отвечает своим именем и путём запроса, а заголовок с меткой
     * возвращает так же, как это делают настоящие сервисы.
     */
    private record Backend(String name, HttpServer server, Map<String, List<String>> lastHeaders) {

        static Backend start(String name) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                Backend backend = new Backend(name, server, new ConcurrentHashMap<>());

                server.createContext("/", backend::handle);
                server.start();

                return backend;
            } catch (IOException exception) {
                throw new IllegalStateException("Не удалось поднять сервер-заглушку " + name, exception);
            }
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        String header(String name) {
            return lastHeaders.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }

        private void handle(HttpExchange exchange) throws IOException {
            lastHeaders.clear();
            lastHeaders.putAll(exchange.getRequestHeaders());

            String correlationId = exchange.getRequestHeaders().getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
            if (correlationId != null) {
                exchange.getResponseHeaders().set(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, correlationId);
            }

            byte[] body = (name + ":" + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
