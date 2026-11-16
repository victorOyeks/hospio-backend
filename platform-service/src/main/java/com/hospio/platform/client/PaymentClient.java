package com.hospio.platform.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    private static final ParameterizedTypeReference<ApiResponse<Summary>> SUMMARY = new ParameterizedTypeReference<>() {
    };

    /** payment's AdminPaymentDtos.HotelTotal; a missing gross reads as zero. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HotelTotal(String hotelId, BigDecimal gross, long count) {
        public HotelTotal {
            gross = gross == null ? BigDecimal.ZERO : gross;
        }
    }

    /** payment's AdminPaymentDtos.Summary, the fields platform uses; missing gross reads as zero, no hotels as empty. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(BigDecimal gross, long count, List<HotelTotal> byHotel) {
        public Summary {
            gross = gross == null ? BigDecimal.ZERO : gross;
            byHotel = byHotel == null ? List.of() : byHotel;
        }

        public static Summary empty() {
            return new Summary(BigDecimal.ZERO, 0, List.of());
        }
    }

    /** GET /internal/admin/summary?businessId=&from=&to= → {gross, count, byHotel: [{hotelId, gross, count}]}. */
    public Summary summary(String businessId, LocalDate from, LocalDate to) {
        try {
            Summary d = TenantClient.data(restClient.get().uri(b -> b.path("/internal/admin/summary").queryParam("businessId", businessId)
                    .queryParam("from", from.toString()).queryParam("to", to.toString()).build()).retrieve().body(SUMMARY));
            return d == null ? Summary.empty() : d;
        } catch (Exception e) {
            warn("GET /internal/admin/summary", e);
            throw new IllegalStateException("payment-service didn't answer the payment summary: " + e.getMessage(), e);
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
