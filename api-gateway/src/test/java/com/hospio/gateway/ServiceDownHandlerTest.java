package com.hospio.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Connection refused to a service is a 503 "try again" in the API's shape; any other error is left to the default handler. */
class ServiceDownHandlerTest {

    private final ServiceDownHandler handler = new ServiceDownHandler();

    @Test
    void connectionRefusedIsA503() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/folio/organizations/abc"));
        handler.handle(exchange, new RuntimeException("wrapped", new ConnectException("Connection refused"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"status\":false").contains(ServiceDownHandler.MESSAGE);
    }

    @Test
    void otherErrorsPassThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/folio/organizations/abc"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(exchange, new IllegalStateException("boom")).block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
