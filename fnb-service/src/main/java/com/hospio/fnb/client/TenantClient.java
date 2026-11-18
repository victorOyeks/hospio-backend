package com.hospio.fnb.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Hospio360 setting up a location's outlets during onboarding (docs/plan-venue-type.md, phase 3): a platform caller has no
 * business, and every row here needs one, so the location's business is looked up and the work runs as the system inside it.
 * Business and hotel callers run as themselves.
 */
@Slf4j
@Component
public class TenantClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hotel(String businessId) {
    }

    private static final ParameterizedTypeReference<ApiResponse<Hotel>> HOTEL = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(tenantUrl, jwtUtil, internalApiKey);
    }

    public <T> T withHotelTenant(Supplier<T> work) {
        Tenant current = TenantContext.current().orElse(null);
        if (current != null && current.businessId() != null) {
            return work.get();
        }
        String hotelId = TenantContext.requireHotel();
        String businessId;
        try {
            ApiResponse<Hotel> body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            businessId = body == null || body.data() == null ? null : body.data().businessId();
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            businessId = null;
        }
        if (businessId == null) {
            throw new ExternalServiceException("Could not find which business location " + hotelId + " belongs to");
        }
        Tenant bound = Tenant.systemFor(businessId, hotelId, current == null ? null : current.actorId(), current == null ? null : current.actorName());
        return TenantContext.runWith(bound, work);
    }
}
