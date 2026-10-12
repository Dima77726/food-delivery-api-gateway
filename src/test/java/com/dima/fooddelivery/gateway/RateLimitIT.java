package com.dima.fooddelivery.gateway;

import com.redis.testcontainers.RedisContainer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ограничитель частоты на настоящем Redis.
 *
 * <p>Счёт ведёт Lua-скрипт внутри Redis, поэтому подмена хранилища ничего бы не проверила.
 * Пороги в тесте крошечные — три запроса в ведре входа, — чтобы упереться в них за три вызова,
 * а не за сотню. Бэкенд — встроенный в JDK {@code HttpServer}, как и в {@link GatewayRoutingIT}.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "app.rate-limit.enabled=true",
                "app.rate-limit.auth.replenish-rate=1",
                "app.rate-limit.auth.burst-capacity=3",
                "app.rate-limit.auth.requested-tokens=1",
                "app.rate-limit.general.replenish-rate=1000",
                "app.rate-limit.general.burst-capacity=1000"
        }
)
class RateLimitIT {

    @Container
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    private static final HttpServer BACKEND = startBackend();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getRedisHost);
        registry.add("spring.data.redis.port", REDIS::getRedisPort);
        registry.add("IDENTITY_SERVICE_URL", () -> "http://localhost:" + BACKEND.getAddress().getPort());
        registry.add("CATALOG_SERVICE_URL", () -> "http://localhost:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    /** Три попытки входа проходят, четвёртая получает 429 с подсказкой, когда повторить. */
    @Test
    void shouldRejectLoginAttemptsOverTheLimit() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(200, get("/api/v1/auth/login").statusCode(), "попытка " + attempt + " в пределах ведра");
        }

        HttpResponse<String> rejected = get("/api/v1/auth/login");

        assertAll(
                () -> assertEquals(429, rejected.statusCode()),
                () -> assertNotNull(rejected.headers().firstValue("Retry-After").orElse(null)),
                () -> assertTrue(rejected.body().contains("\"status\":429")),
                // Метка запроса нужна и отказу: именно по нему клиент придёт с жалобой.
                () -> assertNotNull(rejected.headers().firstValue("X-Correlation-Id").orElse(null))
        );
    }

    /**
     * Ведро входа не съедает общий лимит клиента: исчерпав попытки входа, он по-прежнему
     * читает каталог. Ключи ведёр различаются префиксом — без него они делили бы один счётчик.
     */
    @Test
    void shouldKeepGeneralBucketSeparateFromLogin() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            get("/api/v1/auth/register");
        }

        assertEquals(200, get("/api/v1/restaurants").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path)).GET().build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

            server.createContext("/", exchange -> {
                byte[] body = "ok".getBytes();
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
