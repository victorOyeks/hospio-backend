package com.hospio.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Businesses as tenant-service knows them: one business with its owner and fee model, the platform summary,
 * lists by stage or marketer, onboarding drafts, and the fee write-back. Every call fails soft: a 404 (the
 * route is not there yet) or an unreachable service is logged and answered with an empty value, so the
 * portal still renders.
 */
@Slf4j
@Component
public class TenantClient {

    private final RestClient restClient;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    public record Hotel(String id, String name, String city, int roomCount, String status, String managerUserId) {
    }

    public record Business(String id, String name, String city, String stage, String source, String ownerUserId, String ownerName,
                           String ownerEmail, String ownerPhone, String feeModel, BigDecimal feeValue, String marketerId, String referralId,
                           String accountManagerId, Instant createdAt, int hotelCount, List<Hotel> hotels) {
    }

    public record Summary(long total, Map<String, Long> byStage, long hotels, long rooms) {
        public static Summary empty() {
            Map<String, Long> stages = new LinkedHashMap<>();
            for (String s : List.of("LEAD", "ONBOARDING", "HANDED_OVER", "LIVE", "AT_RISK", "SUSPENDED", "CHURNED")) {
                stages.put(s, 0L);
            }
            return new Summary(0, stages, 0, 0);
        }
    }

    public record Draft(String businessId, String businessName, String source, int step, String status, String assignedTo,
                        Instant startedAt, Instant updatedAt) {
    }

    /** GET /internal/businesses/{id} (exists). */
    public Optional<Business> business(String businessId) {
        try {
            JsonNode body = restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(JsonNode.class);
            JsonNode d = data(body);
            return d == null ? Optional.empty() : Optional.of(business(d));
        } catch (Exception e) {
            warn("GET /internal/businesses/" + businessId, e);
            return Optional.empty();
        }
    }

    /** GET /internal/businesses/summary → {total, byStage, hotels, rooms} (tenant, new). Zeros until it lands. */
    public Summary summary() {
        try {
            JsonNode body = restClient.get().uri("/internal/businesses/summary").retrieve().body(JsonNode.class);
            JsonNode d = data(body);
            if (d == null) {
                return Summary.empty();
            }
            Summary empty = Summary.empty();
            Map<String, Long> stages = new LinkedHashMap<>(empty.byStage());
            JsonNode by = d.path("byStage");
            by.fields().forEachRemaining(f -> stages.put(f.getKey(), f.getValue().asLong()));
            return new Summary(d.path("total").asLong(), stages, d.path("hotels").asLong(), d.path("rooms").asLong());
        } catch (Exception e) {
            warn("GET /internal/businesses/summary", e);
            return Summary.empty();
        }
    }

    /**
     * Businesses as summaries: a marketer's through GET /internal/businesses?marketerId= (tenant), every business
     * in a stage through the platform's paged GET /businesses?stage= read as the system. Empty on failure.
     */
    public List<Business> businesses(String stage, String marketerId) {
        List<Business> out = new ArrayList<>();
        if (marketerId != null) {
            try {
                JsonNode body = restClient.get().uri(b -> b.path("/internal/businesses").queryParam("marketerId", marketerId).build()).retrieve().body(JsonNode.class);
                JsonNode d = data(body);
                if (d != null && d.isArray()) {
                    d.forEach(n -> out.add(business(n)));
                }
            } catch (Exception e) {
                warn("GET /internal/businesses?marketerId=" + marketerId, e);
            }
            return out;
        }
        // Every page, however many businesses there are. A page that fails fails the whole list: a short list read as complete
        // would skip businesses' fee statements and dismiss their alerts.
        for (int page = 0; page < 100_000; page++) {
            final int p = page;
            try {
                JsonNode body = restClient.get().uri(b -> b.path("/businesses").queryParamIfPresent("stage", Optional.ofNullable(stage))
                        .queryParam("page", p).queryParam("size", 100).build()).retrieve().body(JsonNode.class);
                JsonNode d = data(body);
                JsonNode content = d == null ? null : d.path("content");
                if (content == null || !content.isArray() || content.isEmpty()) {
                    break;
                }
                content.forEach(n -> out.add(business(n)));
                if (d.path("last").asBoolean(true)) {
                    break;
                }
            } catch (Exception e) {
                warn("GET /businesses?stage=" + stage, e);
                throw new IllegalStateException("tenant-service didn't list every business (page " + p + "): " + e.getMessage(), e);
            }
        }
        return out;
    }

    /** GET /internal/onboarding?status= → OnboardingResponse[] (tenant, new). Empty until it lands. */
    public List<Draft> onboarding(String status) {
        try {
            JsonNode body = restClient.get().uri(b -> b.path("/internal/onboarding")
                    .queryParamIfPresent("status", Optional.ofNullable(status)).build()).retrieve().body(JsonNode.class);
            JsonNode d = data(body);
            List<Draft> out = new ArrayList<>();
            if (d != null && d.isArray()) {
                d.forEach(n -> out.add(new Draft(n.path("businessId").asText(null), n.path("businessName").asText(null), n.path("source").asText(null),
                        n.path("step").asInt(), n.path("status").asText(null), n.path("assignedTo").asText(null),
                        instant(n.path("startedAt")), instant(n.path("updatedAt")))));
            }
            return out;
        } catch (Exception e) {
            warn("GET /internal/onboarding", e);
            return List.of();
        }
    }

    /** PUT /internal/businesses/{id}/fee {feeModel, feeValue} (tenant, new). Returns false when it could not be written. */
    public boolean setFee(String businessId, String feeModel, BigDecimal feeValue) {
        try {
            restClient.put().uri("/internal/businesses/{id}/fee", businessId).body(Map.of("feeModel", feeModel, "feeValue", feeValue))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            warn("PUT /internal/businesses/" + businessId + "/fee", e);
            return false;
        }
    }

    private static Business business(JsonNode n) {
        List<Hotel> hotels = new ArrayList<>();
        if (n.path("hotels").isArray()) {
            n.path("hotels").forEach(h -> hotels.add(new Hotel(h.path("id").asText(null), h.path("name").asText(null), h.path("city").asText(null),
                    h.path("roomCount").asInt(), h.path("status").asText(null), h.path("managerUserId").asText(null))));
        }
        int hotelCount = n.has("hotelCount") ? n.path("hotelCount").asInt() : n.has("hotels") && n.path("hotels").isNumber() ? n.path("hotels").asInt() : hotels.size();
        BigDecimal feeValue = n.path("feeValue").isNumber() || n.path("feeValue").isTextual() && !n.path("feeValue").asText().isBlank()
                ? new BigDecimal(n.path("feeValue").asText()) : null;
        return new Business(n.path("id").asText(null), n.path("name").asText(null), n.path("city").asText(null), n.path("stage").asText(null),
                n.path("source").asText(null), n.path("ownerUserId").asText(null), n.path("ownerName").asText(null), n.path("ownerEmail").asText(null),
                n.path("ownerPhone").asText(null), n.path("feeModel").asText(null), feeValue, n.path("marketerId").asText(null),
                n.path("referralId").asText(null), n.path("accountManagerId").asText(null), instant(n.path("createdAt")), hotelCount, hotels);
    }

    static JsonNode data(JsonNode body) {
        JsonNode d = body == null ? null : body.path("data");
        return d == null || d.isMissingNode() || d.isNull() ? null : d;
    }

    static Instant instant(JsonNode node) {
        try {
            return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : Instant.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private static void warn(String call, Exception e) {
        if (e instanceof HttpClientErrorException.NotFound) {
            log.warn("tenant-service has no {} yet; answering empty", call);
        } else {
            log.warn("tenant-service {} failed: {}", call, e.getMessage());
        }
    }
}
