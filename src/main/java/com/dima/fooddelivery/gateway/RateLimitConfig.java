package com.dima.fooddelivery.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Два ведра ограничителя частоты; пороги — в {@code app.rate-limit} ({@code application.yml}).
 *
 * <p>Лимитеры создаются здесь, а не берутся готовыми из автоконфигурации шлюза: той нужен один
 * на всё приложение, а нам — два с разными порогами. Redis-шаблон и Lua-скрипт каждый достаёт
 * из контекста сам, как и штатный лимитер.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
public class RateLimitConfig {

    /**
     * Токены в секунду, ёмкость ведра и цена одного запроса в токенах.
     *
     * @param replenishRate  сколько токенов возвращается в секунду
     * @param burstCapacity  сколько запросов подряд клиент может сделать из полного ведра
     * @param requestedTokens цена одного запроса; больше единицы — чтобы выразить «реже одного в секунду»
     */
    public record Limit(int replenishRate, long burstCapacity, int requestedTokens) {
    }

    public record Limits(Limit general, Limit auth) {
    }

    @Bean
    Limits rateLimits(
            @Value("${app.rate-limit.general.replenish-rate:50}") int generalRate,
            @Value("${app.rate-limit.general.burst-capacity:100}") long generalBurst,
            @Value("${app.rate-limit.general.requested-tokens:1}") int generalTokens,
            @Value("${app.rate-limit.auth.replenish-rate:1}") int authRate,
            @Value("${app.rate-limit.auth.burst-capacity:20}") long authBurst,
            @Value("${app.rate-limit.auth.requested-tokens:4}") int authTokens
    ) {
        return new Limits(
                new Limit(generalRate, generalBurst, generalTokens),
                new Limit(authRate, authBurst, authTokens)
        );
    }

    /**
     * {@code @Primary} не ради нашего фильтра — тот берёт лимитеры по имени. Автоконфигурация
     * шлюза создаёт фабрику фильтра {@code RequestRateLimiter} для маршрутов и требует ровно
     * один {@code RateLimiter}; маршруты её не используют, но без этой пометки контекст
     * не поднимется.
     */
    @Bean
    @Primary
    RedisRateLimiter generalRateLimiter(Limits limits) {
        Limit limit = limits.general();

        return new RedisRateLimiter(limit.replenishRate(), limit.burstCapacity(), limit.requestedTokens());
    }

    @Bean
    RedisRateLimiter authRateLimiter(Limits limits) {
        Limit limit = limits.auth();

        return new RedisRateLimiter(limit.replenishRate(), limit.burstCapacity(), limit.requestedTokens());
    }
}
