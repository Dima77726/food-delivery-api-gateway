package com.dima.fooddelivery.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Маршруты живых обновлений не подчиняются общему таймауту ответа.
 *
 * <p>Общий таймаут здесь сжат до секунды, а сервис-заглушка отвечает через две. Обычный путь
 * обязан получить 504, путь потока или долгого запроса — дождаться ответа. Так проверяется
 * именно то, ради чего маршруты выделены: настоящие 15 секунд тест ждать не стал бы.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s"
})
class LiveRoutesIT {

    private static final HttpServer SLOW_BACKEND = startSlowBackend();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + SLOW_BACKEND.getAddress().getPort();
        registry.add("ORDER_SERVICE_URL", () -> url);
        registry.add("NOTIFICATION_SERVICE_URL", () -> url);
        registry.add("MESSENGER_SERVICE_URL", () -> url);
    }

    @AfterAll
    static void stopBackend() {
        SLOW_BACKEND.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/customers/7/orders/9/stream",
            "/api/v1/restaurants/5/orders/stream",
            "/api/v1/deliveries/available/poll",
            "/api/v1/notifications/me/poll",
            "/api/v1/messenger/attachments/0b6d2a58-6a0e-4f1f-9d5e-3f2a1c9b8e77"
    })
    void shouldWaitForSlowLiveResponse(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertEquals(200, response.statusCode());
        assertEquals("slow:" + path, response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/customers/7/orders/9",
            "/api/v1/deliveries/available",
            "/api/v1/notifications/me"
    })
    void shouldStillTimeOutOrdinaryPaths(String path) throws Exception {
        assertEquals(504, get(path).statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpServer startSlowBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }

                byte[] body = ("slow:" + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось поднять сервер-заглушку", exception);
        }
    }
}
