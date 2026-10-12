package com.dima.fooddelivery.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Ограничение частоты запросов: корзина токенов в Redis на каждого клиента.
 *
 * <p>Два ведра с разными порогами. Общее защищает сервисы от одного не в меру усердного клиента.
 * Строгое стоит на {@code /api/v1/auth/**}: вход и регистрация — это перебор паролей и
 * массовое создание учёток, и пять попыток подряд там уже много, а не двадцать в секунду.
 * Строгое ведро расходует по несколько токенов за запрос — так «медленнее одного в секунду»
 * выражается целыми числами.
 *
 * <p><b>Почему глобальный фильтр, а не {@code RequestRateLimiter} в маршрутах.</b> Тот требует
 * живого Redis всегда, даже в тестах шлюза и при запуске из IDE, где его нет. Здесь фильтр
 * подключается только флагом {@code app.rate-limit.enabled}, а сам счётчик — штатный
 * {@link RedisRateLimiter} со своим Lua-скриптом: подсчёт атомарен и общий для всех экземпляров
 * шлюза.
 *
 * <p><b>Ключ — адрес клиента, и только он.</b> Токен шлюз не проверяет (это дело сервисов), так что
 * ключа по пользователю у него нет. {@code X-Forwarded-For} не читается: шлюз стоит на границе,
 * и заголовок от клиента подделывается одной строкой — ограничение обходилось бы сменой значения.
 * Если перед шлюзом появится доверенный балансировщик, ключ придётся брать из его заголовка.
 *
 * <p><b>Недоступный Redis не должен ронять вход.</b> При его отказе лимитер пропускает запрос
 * и пишет в лог; ограничение на это время пропадает, а шлюз работает.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGlobalFilter.class);

    static final String AUTH_PREFIX = "/api/v1/auth/";

    private final RedisRateLimiter generalLimiter;
    private final RedisRateLimiter authLimiter;
    private final int authRetryAfterSeconds;
    private final int generalRetryAfterSeconds;

    public RateLimitGlobalFilter(
            @Qualifier("generalRateLimiter") RedisRateLimiter generalLimiter,
            @Qualifier("authRateLimiter") RedisRateLimiter authLimiter,
            RateLimitConfig.Limits limits
    ) {
        this.generalLimiter = generalLimiter;
        this.authLimiter = authLimiter;
        this.generalRetryAfterSeconds = retryAfter(limits.general());
        this.authRetryAfterSeconds = retryAfter(limits.auth());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean auth = path.startsWith(AUTH_PREFIX);

        RedisRateLimiter limiter = auth ? authLimiter : generalLimiter;
        String bucket = auth ? "auth" : "general";

        // Префикс ведра обязателен: ключи двух лимитеров лежат в одном Redis, и без него
        // вход «съедал» бы общий лимит клиента, а общие запросы — строгий.
        return limiter.isAllowed(bucket, bucket + ":" + clientKey(exchange)).flatMap(response -> {
            response.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));

            if (response.isAllowed()) {
                return chain.filter(exchange);
            }

            log.warn("Превышен лимит запросов: bucket={}, path={}", bucket, path);

            return tooManyRequests(exchange, path, auth ? authRetryAfterSeconds : generalRetryAfterSeconds);
        });
    }

    /** После фильтра метки запроса: отказ тоже должен нести метку. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static String clientKey(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();

        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    /** Через сколько ведро снова наберёт токенов на один запрос. */
    private static int retryAfter(RateLimitConfig.Limit limit) {
        return Math.max(1, (int) Math.ceil((double) limit.requestedTokens() / limit.replenishRate()));
    }

    private static Mono<Void> tooManyRequests(ServerWebExchange exchange, String path, int retryAfterSeconds) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));

        String body = """
                {"status":429,"error":"Too Many Requests","message":"Слишком много запросов, повторите позже","path":"%s"}"""
                .formatted(path.replace("\"", ""));

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
