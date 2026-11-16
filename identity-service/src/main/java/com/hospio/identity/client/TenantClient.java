package com.hospio.identity.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * What identity needs from tenant-service: create the business a new owner signs up with, and know
 * which hotels a business has (the owner's token carries all of them; a hotel role must name one of
 * them). Always calls as the system: these are identity's own lookups, made while signing people in.
 */
@Slf4j
@Component
public class TenantClient {

    private static final ParameterizedTypeReference<ApiResponse<List<HotelInfo>>> HOTELS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<HotelInfo>> HOTEL = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<BusinessName>> BUSINESS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    /** The fields identity reads of tenant's HotelResponse. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HotelInfo(String id, String businessId, String name) {
    }

    /** The one field identity reads of tenant's BusinessResponse. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessName(String name) {
    }

    public record NewBusiness(String id, String name, String city, String ownerUserId, String ownerName, String ownerEmail,
                              String ownerPhone, String source) {
    }

    /** Sign-up: the business with a preset id. Fails loudly; without a business the owner cannot exist. */
    public void createBusiness(NewBusiness business) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", business.id());
            body.put("name", business.name());
            body.put("city", business.city() == null ? "" : business.city());
            body.put("ownerUserId", business.ownerUserId());
            body.put("ownerName", business.ownerName());
            body.put("ownerEmail", business.ownerEmail());
            body.put("ownerPhone", business.ownerPhone() == null ? "" : business.ownerPhone());
            body.put("source", business.source());
            restClient.post().uri("/internal/businesses").body(body).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.error("Could not create business {} in tenant-service", business.name(), e);
            throw new ExternalServiceException("Could not create the business right now", e);
        }
    }

    /** Every hotel id of a business; empty Optional when tenant-service cannot be reached. */
    public Optional<List<String>> hotelIds(String businessId) {
        try {
            ApiResponse<List<HotelInfo>> body = restClient.get().uri("/internal/businesses/{id}/hotels", businessId).retrieve().body(HOTELS);
            List<String> ids = new ArrayList<>();
            if (body != null && body.data() != null) {
                body.data().forEach(h -> ids.add(h.id() == null ? "" : h.id()));
            }
            return Optional.of(ids);
        } catch (Exception e) {
            log.warn("Could not list hotels of business {}: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<HotelInfo> hotel(String hotelId) {
        try {
            ApiResponse<HotelInfo> body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            HotelInfo data = body == null ? null : body.data();
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(new HotelInfo(data.id() == null ? "" : data.id(), data.businessId(), data.name()));
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> businessName(String businessId) {
        try {
            ApiResponse<BusinessName> body = restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(BUSINESS);
            return Optional.ofNullable(body == null || body.data() == null ? null : body.data().name());
        } catch (Exception e) {
            log.warn("Could not read business {}: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }
}
