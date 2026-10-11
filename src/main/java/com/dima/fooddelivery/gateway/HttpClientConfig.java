package com.dima.fooddelivery.gateway;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Настройки HTTP-клиента, которым шлюз ходит в сервисы.
 *
 * <p><b>Разрешение имён — через JVM, а не через собственный резолвер Netty.</b> Netty по
 * умолчанию держит свой DNS-кэш и помнит адрес столько, сколько разрешил DNS-сервер.
 * В Docker и Kubernetes адрес сервиса меняется при каждом перезапуске контейнера, и шлюз
 * продолжал бы стучаться в адрес, которого уже нет: сервис давно поднялся, а клиенты всё
 * получают «Connection refused». Ровно так и случилось на первом же запуске стека — монолит
 * перезапустился, сменил адрес, а шлюз отвечал ошибкой, пока его самого не перезапустили.
 *
 * <p>Резолвер JVM кэширует успешный ответ на 30 секунд (networkaddress.cache.ttl по
 * умолчанию), так что новый адрес шлюз увидит не позже чем через полминуты. Цена —
 * блокирующий вызов в отдельном потоке на каждое разрешение имени, но после кэша это
 * случается раз в 30 секунд на сервис, а не на каждый запрос.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    HttpClientCustomizer jvmAddressResolver() {
        return httpClient -> httpClient.resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
