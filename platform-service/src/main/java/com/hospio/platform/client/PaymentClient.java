package com.hospio.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Approved payments per business and hotel for a period, from payment-service. Zeros when unreachable. */
@Slf4j
@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${services.payment.url}") String paymentUrl, JwtUtil jwtUtil,
                         @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(paymentUrl, jwtUtil, internalApiKey);
    }

    public record HotelTotal(String hotelId, BigDecimal gross, long count) {
    }

    public record Summary(BigDecimal gross, long count, List<HotelTotal> byHotel) {
        public static Summary empty() {
            return new Summary(BigDecimal.ZERO, 0, List.of());
        }
    }

    /** GET /internal/admin/summary?businessId=&from=&to= → {gross, count, byHotel: [{hotelId, gross, count}]}. */
    public Summary summary(String businessId, LocalDate from, LocalDate to) {
        try {
            JsonNode body = restClient.get().uri(b -> b.path("/internal/admin/summary").queryParam("businessId", businessId)
                    .queryParam("from", from.toString()).queryParam("to", to.toString()).build()).retrieve().body(JsonNode.class);
            JsonNode d = TenantClient.data(body);
            if (d == null) {
                return Summary.empty();
            }
            List<HotelTotal> hotels = new ArrayList<>();
            d.path("byHotel").forEach(h -> hotels.add(new HotelTotal(h.path("hotelId").asText(null), new BigDecimal(h.path("gross").asText("0")), h.path("count").asLong())));
            return new Summary(new BigDecimal(d.path("gross").asText("0")), d.path("count").asLong(), hotels);
        } catch (Exception e) {
            warn("GET /internal/admin/summary", e);
            return Summary.empty();
        }
    }

    private static void warn(String call, Exception e) {
        if (e instanceof HttpClientErrorException.NotFound) {
            log.warn("payment-service has no {} yet; answering zero", call);
        } else {
            log.warn("payment-service {} failed: {}", call, e.getMessage());
        }
    }
}
