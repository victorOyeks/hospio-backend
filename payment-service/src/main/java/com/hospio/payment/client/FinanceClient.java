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

    /** finance-service's answer to GET /internal/tills/open. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenTill(Boolean open, String tillId) {
    }

    private static final ParameterizedTypeReference<ApiResponse<OpenTill>> OPEN = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public FinanceClient(@Value("${services.finance.url}") String financeUrl, JwtUtil jwtUtil,
                         @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(financeUrl, jwtUtil, internalApiKey);
    }

    /** True or false when finance-service answered; empty when it couldn't be reached (the caller lets the cash through). */
    public Optional<Boolean> tillOpen(String hotelId, String role) {
        try {
            ApiResponse<OpenTill> body = restClient.get().uri(u -> u.path("/internal/tills/open").queryParam("hotelId", hotelId)
                    .queryParam("role", role).build()).retrieve().body(OPEN);
            return Optional.of(body != null && body.data() != null && Boolean.TRUE.equals(body.data().open()));
        } catch (Exception e) {
            log.warn("Could not ask finance-service whether the {} till is open at hotel {}: {}", role, hotelId, e.getMessage());
            return Optional.empty();
        }
    }
}
