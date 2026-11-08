package com.hospio.identity.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    private final RestClient restClient;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    public record HotelInfo(String id, String businessId, String name) {
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
            JsonNode body = restClient.get().uri("/internal/businesses/{id}/hotels", businessId).retrieve().body(JsonNode.class);
            List<String> ids = new ArrayList<>();
            if (body != null) {
                body.path("data").forEach(h -> ids.add(h.path("id").asText()));
            }
            return Optional.of(ids);
        } catch (Exception e) {
            log.warn("Could not list hotels of business {}: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<HotelInfo> hotel(String hotelId) {
        try {
            JsonNode body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new HotelInfo(data.path("id").asText(), data.path("businessId").asText(null), data.path("name").asText(null)));
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> businessName(String businessId) {
        try {
            JsonNode body = restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(JsonNode.class);
            return Optional.ofNullable(body == null ? null : body.path("data").path("name").asText(null));
        } catch (Exception e) {
            log.warn("Could not read business {}: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }
}
