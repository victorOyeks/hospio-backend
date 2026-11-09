package com.hospio.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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

    public record Nights(long nights, BigDecimal value) {
        public static Nights empty() {
            return new Nights(0, BigDecimal.ZERO);
        }
    }

    /** GET /internal/admin/nights?businessId=&from=&to= → {nights, value} (reservation, new). */
    public Nights nights(String businessId, LocalDate from, LocalDate to) {
        try {
            JsonNode body = restClient.get().uri(b -> b.path("/internal/admin/nights").queryParam("businessId", businessId)
                    .queryParam("from", from.toString()).queryParam("to", to.toString()).build()).retrieve().body(JsonNode.class);
            JsonNode d = TenantClient.data(body);
            return d == null ? Nights.empty() : new Nights(d.path("nights").asLong(), new BigDecimal(d.path("value").asText("0")));
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException.NotFound) {
                log.warn("reservation-service has no GET /internal/admin/nights yet; answering zero");
            } else {
                log.warn("reservation-service nights failed: {}", e.getMessage());
            }
            return Nights.empty();
        }
    }
}
