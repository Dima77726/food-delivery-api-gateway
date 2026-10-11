package com.dima.fooddelivery.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сервис за шлюзом не отвечает — клиент получает 503 с меткой запроса, а не 500.
 *
 * <p>Адрес сервиса указывает на порт, который только что был свободен и закрыт: соединение
 * на него отклоняется ровно так же, как на остановленный контейнер.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class UnavailableServiceIT {

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void deadBackends(DynamicPropertyRegistry registry) {
        String dead = "http://localhost:" + closedPort();

        registry.add("CORE_SERVICE_URL", () -> dead);
        registry.add("CATALOG_SERVICE_URL", () -> dead);
        registry.add("IDENTITY_SERVICE_URL", () -> dead);
        registry.add("NOTIFICATION_SERVICE_URL", () -> dead);
    }

    @Test
    void shouldAnswerServiceUnavailableWhenBackendRefusesConnection() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + "/api/v1/restaurants")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertAll(
                () -> assertEquals(503, response.statusCode()),
                () -> assertTrue(response.headers().firstValue("Retry-After").isPresent()),
                () -> assertTrue(
                        response.headers().firstValue(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER).isPresent(),
                        "метка запроса нужна клиенту именно тогда, когда что-то пошло не так"
                ),
                () -> assertTrue(response.body().contains("\"status\":503"))
        );
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
