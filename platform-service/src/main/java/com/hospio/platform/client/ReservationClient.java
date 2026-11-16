package com.hospio.platform.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Room-nights sold and booking value per business for a period, from reservation-service. Zeros when unreachable. */
@Slf4j
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String reservationUrl, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(reservationUrl, jwtUtil, internalApiKey);
    }

    private static final ParameterizedTypeReference<ApiResponse<Nights>> NIGHTS = new ParameterizedTypeReference<>() {
    };

    /** reservation's AdminNightsResponse; a missing value reads as zero. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nights(long nights, BigDecimal value) {
        public Nights {
            value = value == null ? BigDecimal.ZERO : value;
        }

        public static Nights empty() {
            return new Nights(0, BigDecimal.ZERO);
        }
    }

    /** GET /internal/admin/nights?businessId=&from=&to= → {nights, value} (reservation, new). */
    public Nights nights(String businessId, LocalDate from, LocalDate to) {
        try {
            Nights d = TenantClient.data(restClient.get().uri(b -> b.path("/internal/admin/nights").queryParam("businessId", businessId)
                    .queryParam("from", from.toString()).queryParam("to", to.toString()).build()).retrieve().body(NIGHTS));
            return d == null ? Nights.empty() : d;
        } catch (Exception e) {
            // Never zero on a failure: a fee statement made from it would bill nothing and never be made again.
            log.warn("reservation-service nights failed for business {}: {}", businessId, e.getMessage());
            throw new IllegalStateException("reservation-service didn't answer room-nights: " + e.getMessage(), e);
        }
    }
}
