package com.hospio.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Whether a desk's till is open at a hotel (finance-service), so cash is only taken into an open till. */
@Slf4j
@Component
public class FinanceClient {

    /**
     * finance-service's answer to GET /internal/tills/open: whether the desk's till is open, which one, who opened it and when, and
     * whether it was left open from an earlier day ({@code stale}: cash can't go into it until it is counted and closed).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TillState(Boolean open, String tillId, Boolean stale, java.time.Instant openedAt, String openedBy, String openedByName) {
        public boolean isOpen() {
            return Boolean.TRUE.equals(open);
        }

        public boolean isStale() {
            return isOpen() && Boolean.TRUE.equals(stale);
        }

        public static TillState closed() {
            return new TillState(false, null, false, null, null, null);
        }

        public static TillState openNow(String openedByName) {
            return new TillState(true, "t1", false, java.time.Instant.now(), null, openedByName);
        }
    }

    private static final ParameterizedTypeReference<ApiResponse<TillState>> OPEN = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public FinanceClient(@Value("${services.finance.url}") String financeUrl, JwtUtil jwtUtil,
                         @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(financeUrl, jwtUtil, internalApiKey);
    }

    /** The desk's till when finance-service answered (closed when it has none open); empty when it couldn't be reached (the caller lets the cash through). */
    public Optional<TillState> till(String hotelId, String role) {
        try {
            ApiResponse<TillState> body = restClient.get().uri(u -> u.path("/internal/tills/open").queryParam("hotelId", hotelId)
                    .queryParam("role", role).build()).retrieve().body(OPEN);
            return Optional.of(body == null || body.data() == null ? TillState.closed() : body.data());
        } catch (Exception e) {
            log.warn("Could not ask finance-service whether the {} till is open at hotel {}: {}", role, hotelId, e.getMessage());
            return Optional.empty();
        }
    }
}
