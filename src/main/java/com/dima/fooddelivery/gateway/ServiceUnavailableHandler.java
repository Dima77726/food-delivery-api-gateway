package com.dima.fooddelivery.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Недоступный сервис — это 503, а не 500.
 *
 * <p>По умолчанию шлюз отвечает на «соединение отклонено» внутренней ошибкой, и для клиента
 * это неотличимо от поломки самого шлюза. А правда в другом: шлюз исправен, один сервис
 * за ним сейчас не отвечает — перезапускается, выкатывается, упал. 503 говорит ровно это,
 * и клиент — или балансировщик перед шлюзом — вправе повторить запрос позже.
 *
 * <p>Ловится только то, что действительно означает «сервис недоступен»: ошибка сокета
 * (отказ в соединении, нет маршрута, разрыв до ответа) и неразрешимое имя. Именно сокетная,
 * а не только {@code ConnectException}: отказ на закрытый порт выглядит по-разному на разных
 * системах — на Linux это «Connection refused», на Windows бывает «Permission denied», — и
 * привязка к одному классу исключения работала бы на стенде и не работала на ноутбуке.
 * Таймаут ответа шлюз и так превращает в 504, а прочие ошибки остаются обработчику
 * по умолчанию — выдавать их за недоступность значило бы скрывать настоящие баги.
 *
 * <p>{@code @Order(-2)} ставит обработчик раньше стандартного обработчика Spring Boot
 * ({@code -1}), иначе тот успел бы ответить 500 первым. Метку запроса в ответ добавит
 * {@link CorrelationIdGlobalFilter} — он подписан на момент отправки ответа.
 */
@Component
@Order(-2)
public class ServiceUnavailableHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ServiceUnavailableHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (!isUnreachable(failure) || exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }

        String path = exchange.getRequest().getPath().value();
        log.warn("Сервис недоступен: path={}, reason={}", path, failure.getMessage());

        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Повторить имеет смысл не сразу: сервису нужно время подняться.
        exchange.getResponse().getHeaders().set("Retry-After", "5");

        String body = """
                {"status":503,"error":"Service Unavailable","message":"Сервис временно недоступен, повторите позже","path":"%s"}"""
                .formatted(path.replace("\"", ""));

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Причина может быть завёрнута на несколько уровней — Netty и Reactor любят обёртки. */
    private boolean isUnreachable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof UnknownHostException) {
                return true;
            }
        }

        return false;
    }
}
