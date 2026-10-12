package com.dima.fooddelivery.gateway;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Служебные запросы не наблюдаются: ни спанов, ни метрик {@code http.server.requests}.
 *
 * <p>Prometheus опрашивает {@code /actuator/prometheus} каждые пять секунд, а оркестратор —
 * пробы здоровья. Каждый такой запрос без этого фильтра становится трассой, и поиск в Tempo
 * тонет в служебном шуме, а настоящие запросы приходится искать среди тысяч пустых.
 *
 * <p>Служебные эндпоинты живут на отдельном порту и снаружи недоступны, так что реальные
 * запросы пользователей этот фильтр не затрагивает.
 */
@Configuration(proxyBeanMethods = false)
public class ActuatorObservationConfig {

    @Bean
    ObservationPredicate skipActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getPath().value().startsWith("/actuator"));
    }
}
