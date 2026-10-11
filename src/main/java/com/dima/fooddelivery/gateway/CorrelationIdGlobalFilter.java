package com.dima.fooddelivery.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Рождение сквозной метки запроса.
 *
 * <p>Шлюз — первая точка, через которую проходит любой внешний запрос, поэтому метку
 * разумно ставить здесь: дальше она едет заголовком в сервис, оттуда через outbox и Kafka
 * в потребителей, и по одному значению находятся все следы запроса во всех сервисах.
 *
 * <p>Метка клиента принимается, но очищается: она попадает в логи и базы всех сервисов,
 * и перевод строки в ней позволил бы подделать запись лога. Набор допустимых символов
 * и длина — те же, что в сервисах: разойдись правила, и сервис отрезал бы метку иначе,
 * чем шлюз, а сшивка по ней перестала бы работать.
 *
 * <p>В ответ метка ставится перед отправкой, а не сразу. Сервис возвращает её сам, и шлюз
 * копирует его заголовки в ответ — поставь он метку заранее, клиент получил бы её дважды.
 * {@code set} перед коммитом ответа оставляет ровно одну, а если сервис не ответил вовсе
 * (503, таймаут), метка всё равно дойдёт до клиента — в этот момент она нужнее всего.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final int MAX_LENGTH = 64;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CORRELATION_ID_HEADER, correlationId))
                .build();

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(request).build());
    }

    /** Раньше всех остальных фильтров: метка нужна даже запросу, который отвергнут дальше. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolve(String fromHeader) {
        if (fromHeader == null || fromHeader.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String sanitized = fromHeader.replaceAll("[^A-Za-z0-9\\-_]", "");

        if (sanitized.isEmpty()) {
            return UUID.randomUUID().toString();
        }

        return sanitized.length() > MAX_LENGTH ? sanitized.substring(0, MAX_LENGTH) : sanitized;
    }
}
