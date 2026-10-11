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
 * Шлюз против настоящих HTTP-серверов, изображающих монолит и вынесенные сервисы.
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

    private static final Backend INSIGHTS = Backend.start("insights-service");
    private static final Backend NOTIFICATIONS = Backend.start("notification-service");
    private static final Backend CATALOG = Backend.start("catalog-service");
    private static final Backend IDENTITY = Backend.start("identity-service");
    private static final Backend ORDERS = Backend.start("order-service");
    private static final Backend PAYMENTS = Backend.start("payment-service");
    private static final Backend TRACKING = Backend.start("tracking-service");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        registry.add("INSIGHTS_SERVICE_URL", INSIGHTS::url);
        registry.add("NOTIFICATION_SERVICE_URL", NOTIFICATIONS::url);
        registry.add("CATALOG_SERVICE_URL", CATALOG::url);
        registry.add("IDENTITY_SERVICE_URL", IDENTITY::url);
        registry.add("ORDER_SERVICE_URL", ORDERS::url);
        registry.add("PAYMENT_SERVICE_URL", PAYMENTS::url);
        registry.add("TRACKING_SERVICE_URL", TRACKING::url);
    }

    @AfterAll
    static void stopBackends() {
        INSIGHTS.server.stop(0);
        NOTIFICATIONS.server.stop(0);
        CATALOG.server.stop(0);
        IDENTITY.server.stop(0);
        ORDERS.server.stop(0);
        PAYMENTS.server.stop(0);
        TRACKING.server.stop(0);
    }

    @BeforeEach
    void resetBackends() {
        INSIGHTS.lastHeaders.clear();
        NOTIFICATIONS.lastHeaders.clear();
        CATALOG.lastHeaders.clear();
        IDENTITY.lastHeaders.clear();
        ORDERS.lastHeaders.clear();
        PAYMENTS.lastHeaders.clear();
        TRACKING.lastHeaders.clear();
    }

    @Test
    void shouldRouteNotificationsToNotificationService() throws Exception {
        HttpResponse<String> response = send("/api/v1/notifications/me", Map.of());

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertEquals("notification-service:/api/v1/notifications/me", response.body()),
                () -> assertTrue(INSIGHTS.lastHeaders.isEmpty(), "insights-service не должен был получить запрос")
        );
    }

    /** Рекомендации клиенту — insights-service, по тому же пути, что был у монолита. */
    @Test
    void shouldRouteRecommendationsToInsights() throws Exception {
        HttpResponse<String> response = send("/api/v1/customers/7/recommendations?limit=5", Map.of());

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertEquals("insights-service:/api/v1/customers/7/recommendations?limit=5", response.body()),
                () -> assertTrue(NOTIFICATIONS.lastHeaders.isEmpty()),
                () -> assertTrue(ORDERS.lastHeaders.isEmpty())
        );
    }

    /** Корзина, заказы и доставки — в сервисе заказов; пути прежние. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/customers/7/restaurants/5/cart",
            "/api/v1/customers/7/restaurants/5/cart/items/3",
            "/api/v1/customers/7/restaurants/5/orders",
            "/api/v1/customers/7/orders?size=5",
            "/api/v1/customers/7/orders/feed",
            "/api/v1/customers/7/orders/9",
            "/api/v1/customers/7/orders/9/events",
            "/api/v1/restaurants/5/orders",
            "/api/v1/restaurants/5/orders/feed",
            "/api/v1/restaurants/5/orders/9/accept",
            "/api/v1/restaurants/5/orders/9/deliveries",
            "/api/v1/deliveries/available",
            "/api/v1/couriers/4/deliveries",
            "/api/v1/couriers/4/deliveries/2/pick-up"
    })
    void shouldRouteOrderPathsToOrderService(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("order-service:" + path, response.body());
    }

    /**
     * Один путь — два владельца. Платежи читает payment-service, а запуск оплаты — это шаг
     * саги, и он принадлежит сервису заказов. Различает их только метод.
     */
    @Test
    void shouldSplitPaymentsByMethod() throws Exception {
        String path = "/api/v1/customers/7/orders/9/payments";

        HttpResponse<String> read = send(path, Map.of());
        HttpResponse<String> pay = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        HttpResponse<String> history = send("/api/v1/customers/7/payments", Map.of());

        assertAll(
                () -> assertEquals("payment-service:" + path, read.body()),
                () -> assertEquals("order-service:" + path, pay.body()),
                () -> assertEquals("payment-service:/api/v1/customers/7/payments", history.body())
        );
    }

    /**
     * Трек лежит под заказами и доставками, но принадлежит сервису трека. Маршрут сервиса
     * заказов /customers/{id}/orders/** забрал бы его, ошибись порядок.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/customers/7/orders/9/track",
            "/api/v1/couriers/4/deliveries/2/track",
            "/api/v1/couriers/4/deliveries/2/positions"
    })
    void shouldRouteTrackPathsToTrackingService(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("tracking-service:" + path, response.body());
    }

    /** Отзыв о заказе — insights-service, хотя путь тоже под заказом. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/customers/7/orders/9/review"
    })
    void shouldKeepOrderScopedMonolithPathsOnCore(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("insights-service:" + path, response.body());
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

    /** Вход, регистрация и администрирование пользователей — в сервис пользователей. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/me",
            "/api/v1/admin/users",
            "/api/v1/admin/users/7/roles/COURIER"
    })
    void shouldRouteIdentityPathsToIdentityService(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("identity-service:" + path, response.body());
    }

    /**
     * Под {@code /restaurants/{id}} живут и чужие для каталога данные: отзывы и аналитика.
     * Они принадлежат insights-service, хотя путь начинается с ресторана.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/restaurants/5/reviews",
            "/api/v1/restaurants/5/reviews/summary",
            "/api/v1/restaurants/5/analytics/sales"
    })
    void shouldKeepRestaurantScopedMonolithPathsOnCore(String path) throws Exception {
        HttpResponse<String> response = send(path, Map.of());

        assertEquals("insights-service:" + path, response.body());
    }

    /**
     * GraphQL, песочница и прочие пути insights-service. Журнал аудита переехал сюда из
     * сервиса заказов: теперь он сводный, со всех сервисов.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/graphql",
            "/graphiql",
            "/api/v1/menu-items/100/ordered-together",
            "/api/v1/admin/analytics/sales",
            "/api/v1/admin/audit?service=order-service&resourceType=ORDER&resourceId=9",
            "/api/v1/admin/recommendations/rebuild"
    })
    void shouldRouteInsightsPaths(String path) throws Exception {
        assertEquals("insights-service:" + path, send(path, Map.of()).body());
    }

    /** Монолита больше нет, а с ним и маршрута «всё остальное»: незнакомый путь — 404. */
    @Test
    void shouldAnswerNotFoundForUnknownPath() throws Exception {
        assertEquals(404, send("/api/v1/no-such-thing", Map.of()).statusCode());
    }

    @Test
    void shouldGenerateCorrelationIdAndPassItToService() throws Exception {
        HttpResponse<String> response = send("/api/v1/ping", Map.of());

        String returned = response.headers().firstValue(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER).orElse(null);
        List<String> returnedAll = response.headers().allValues(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);

        assertAll(
                () -> assertNotNull(returned, "клиент обязан получить метку"),
                () -> assertEquals(1, returnedAll.size(), "метка не должна дублироваться в ответе"),
                () -> assertEquals(returned, INSIGHTS.header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER),
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

        assertEquals("abc-123script", INSIGHTS.header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER));
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
