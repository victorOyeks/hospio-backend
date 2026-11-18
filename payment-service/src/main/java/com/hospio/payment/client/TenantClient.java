package com.hospio.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Business names for settlement and payout rows (cached for the process lifetime: names rarely change), and which business a hotel is in. */
@Slf4j
@Component
public class TenantClient {

    /** The part of tenant-service's business this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessRef(String name) {
    }

    private static final ParameterizedTypeReference<ApiResponse<BusinessRef>> BUSINESS = new ParameterizedTypeReference<>() {
    };

    /** The part of tenant-service's hotel this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HotelRef(String id, String businessId) {
    }

    private static final ParameterizedTypeReference<ApiResponse<HotelRef>> HOTEL = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final Map<String, String> names = new ConcurrentHashMap<>();

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    /**
     * The business a hotel belongs to, empty when tenant-service has no such hotel. Unreachable is an error, not "no hotel", so a
     * platform admin's write is refused with a reason instead of being saved without a business.
     */
    public Optional<String> businessOf(String hotelId) {
        try {
            ApiResponse<HotelRef> body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            return Optional.ofNullable(body == null || body.data() == null ? null : body.data().businessId());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read hotel {} from tenant-service: {}", hotelId, e.getMessage());
            throw new com.hospio.common.exception.ExternalServiceException("Could not check the hotel right now. Try again in a minute");
        }
    }

    public Optional<String> businessName(String businessId) {
        if (businessId == null) {
            return Optional.empty();
        }
        String cached = names.get(businessId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ApiResponse<BusinessRef> body = restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(BUSINESS);
            String name = body == null || body.data() == null ? null : body.data().name();
            if (name != null) {
                names.put(businessId, name);
            }
            return Optional.ofNullable(name);
        } catch (Exception e) {
            log.warn("Could not read business {} from tenant-service: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }
}
