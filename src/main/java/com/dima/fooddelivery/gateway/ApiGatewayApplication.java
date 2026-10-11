package com.dima.fooddelivery.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Шлюз — единственная дверь в систему снаружи.
 *
 * <p>Клиент знает один адрес и не знает, сколько за ним сервисов и где они живут. Это
 * и делает возможным постепенный вынос модулей из монолита: маршрут переключается здесь,
 * а у клиента не меняется ни один URL.
 *
 * <p>Чего шлюз намеренно не делает — бизнес-логики и проверки прав доступа к данным.
 * Шлюз, который знает, чей это заказ, становится новым монолитом, только распределённым.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
