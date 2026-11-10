package com.hospio.gateway;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
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

/**
 * A service that can't be reached (restarting during a deploy, or down) is answered 503 "try again" in the usual response shape,
 * not the default 500 with a stack trace. Runs before Spring's default error handler (order -2); anything else passes through.
 */
@Component
@Order(-3)
public class ServiceDownHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ServiceDownHandler.class);

    static final String MESSAGE = "This part of Hospio is restarting. Try again in a minute.";

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!unreachable(ex) || exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        log.warn("{} {} -> 503, service unreachable: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex.getMessage());
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", "30");
        DataBuffer body = exchange.getResponse().bufferFactory().wrap(body().getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(body));
    }

    static String body() {
        return "{\"requestTime\":\"" + Instant.now() + "\",\"requestType\":\"API\",\"referenceId\":\"" + UUID.randomUUID()
                + "\",\"status\":false,\"message\":\"" + MESSAGE + "\",\"data\":null}";
    }

    /** Connection refused anywhere in the cause chain. */
    static boolean unreachable(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}
