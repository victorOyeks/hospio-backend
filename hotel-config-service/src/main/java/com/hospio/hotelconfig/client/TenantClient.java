package com.hospio.hotelconfig.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.setup.SetupTemplateSettings;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Which business a hotel belongs to (so platform callers can write its settings), and setup templates by id. */
@Slf4j
@Component
public class TenantClient {

    /** tenant-service's hotel, as far as hotel-config needs it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HotelRef(String businessId) {
    }

    /** tenant-service's setup template: {@code settings} is the template's JSON text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TemplateRef(String settings) {
    }

    private static final ParameterizedTypeReference<ApiResponse<HotelRef>> HOTEL = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<TemplateRef>> TEMPLATE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey, ObjectMapper objectMapper) {
        this.restClient = ServiceClients.internal(tenantUrl, jwtUtil, internalApiKey);
        this.objectMapper = objectMapper;
    }

    public Optional<String> businessOf(String hotelId) {
        try {
            ApiResponse<HotelRef> body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            return Optional.ofNullable(body == null || body.data() == null ? null : body.data().businessId());
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    /** tenant's business, the fee fields used here. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessFee(String feeModel, java.math.BigDecimal feeValue) {
    }

    private static final org.springframework.core.ParameterizedTypeReference<ApiResponse<BusinessFee>> BUSINESS_FEE = new org.springframework.core.ParameterizedTypeReference<>() {
    };

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Map.Entry<java.time.Instant, java.math.BigDecimal>> feeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The hotel's business fee rate (% of daily sales), cached a minute; empty when unknown or unreachable. */
    public Optional<java.math.BigDecimal> feeRate(String hotelId) {
        var hit = feeCache.get(hotelId);
        if (hit != null && hit.getKey().isAfter(java.time.Instant.now())) {
            return Optional.ofNullable(hit.getValue());
        }
        try {
            Optional<String> business = businessOf(hotelId);
            if (business.isEmpty()) {
                return Optional.empty();
            }
            ApiResponse<BusinessFee> body = restClient.get().uri("/internal/businesses/{id}", business.get()).retrieve().body(BUSINESS_FEE);
            java.math.BigDecimal rate = body == null || body.data() == null ? null : body.data().feeValue();
            feeCache.put(hotelId, java.util.Map.entry(java.time.Instant.now().plusSeconds(60), rate == null ? java.math.BigDecimal.ZERO : rate));
            return Optional.ofNullable(rate);
        } catch (Exception e) {
            log.warn("Could not read the fee rate for hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The template's settings, read; empty when unknown, unreadable or unreachable. */
    public Optional<SetupTemplateSettings> template(String templateId) {
        try {
            ApiResponse<TemplateRef> body = restClient.get().uri("/internal/setup-templates/{id}", templateId).retrieve().body(TEMPLATE);
            String settings = body == null || body.data() == null ? null : body.data().settings();
            if (settings == null || settings.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(settings, SetupTemplateSettings.class));
        } catch (Exception e) {
            log.warn("Could not read setup template {}: {}", templateId, e.getMessage());
            return Optional.empty();
        }
    }
}
