package com.hospio.platform.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
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
import org.springframework.core.ParameterizedTypeReference;
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

    private static final ParameterizedTypeReference<ApiResponse<Business>> BUSINESS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<SummaryData>> SUMMARY = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<BusinessRow>>> ROWS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<BusinessPage>> PAGE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<Draft>>> DRAFTS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<DraftCounts>> DRAFT_COUNTS = new ParameterizedTypeReference<>() {
    };

    /** tenant's HotelResponse, the fields platform uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hotel(String id, String name, String city, int roomCount, String status, String managerUserId) {
    }

    /** tenant's BusinessResponse (GET /internal/businesses/{id}), the fields platform uses; no hotel list reads as empty. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Business(String id, String name, String city, String stage, String source, String ownerUserId, String ownerName,
                           String ownerEmail, String ownerPhone, String feeModel, BigDecimal feeValue, String marketerId, String referralId,
                           String accountManagerId, Instant createdAt, int hotelCount, List<Hotel> hotels) {
        public Business {
            hotels = hotels == null ? List.of() : hotels;
        }
    }

    /**
     * tenant's BusinessSummary (a list row: GET /internal/businesses?marketerId= and the paged GET /businesses), where {@code hotels}
     * is a count. Read as a {@link Business} with no hotel list.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessRow(String id, String name, String stage, String source, String city, int hotels, String ownerName, String ownerEmail,
                       String accountManagerId, String marketerId, String feeModel, BigDecimal feeValue, Instant createdAt) {
        Business toBusiness() {
            return new Business(id, name, city, stage, source, null, ownerName, ownerEmail, null, feeModel, feeValue, marketerId, null,
                    accountManagerId, createdAt, hotels, List.of());
        }
    }

    /** One page of GET /businesses (tenant's PageWithCounts): only the rows and whether it was the last; no {@code last} reads as last. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessPage(List<BusinessRow> content, Boolean last) {
    }

    /** tenant's BusinessesSummaryResponse. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SummaryData(long total, Map<String, Long> byStage, long hotels, long rooms) {
    }

    /** The paged onboarding board (GET /onboarding?page=0&size=1): only its counts per status. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftCounts(Map<String, Long> counts) {
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

    /** tenant's OnboardingResponse, the fields platform uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Draft(String businessId, String businessName, String source, int step, String status, String assignedTo,
                        Instant startedAt, Instant updatedAt) {
    }

    /** GET /internal/businesses/{id} (exists). */
    public Optional<Business> business(String businessId) {
        try {
            return Optional.ofNullable(data(restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(BUSINESS)));
        } catch (Exception e) {
            warn("GET /internal/businesses/" + businessId, e);
            return Optional.empty();
        }
    }

    /** GET /internal/businesses/summary → {total, byStage, hotels, rooms} (tenant, new). Zeros until it lands. */
    public Summary summary() {
        try {
            SummaryData d = data(restClient.get().uri("/internal/businesses/summary").retrieve().body(SUMMARY));
            if (d == null) {
                return Summary.empty();
            }
            Map<String, Long> stages = new LinkedHashMap<>(Summary.empty().byStage());
            if (d.byStage() != null) {
                d.byStage().forEach((stage, count) -> stages.put(stage, count == null ? 0L : count));
            }
            return new Summary(d.total(), stages, d.hotels(), d.rooms());
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
                List<BusinessRow> d = data(restClient.get().uri(b -> b.path("/internal/businesses").queryParam("marketerId", marketerId).build())
                        .retrieve().body(ROWS));
                if (d != null) {
                    d.forEach(n -> out.add(n.toBusiness()));
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
                BusinessPage d = data(restClient.get().uri(b -> b.path("/businesses").queryParamIfPresent("stage", Optional.ofNullable(stage))
                        .queryParam("page", p).queryParam("size", 100).build()).retrieve().body(PAGE));
                if (d == null || d.content() == null || d.content().isEmpty()) {
                    break;
                }
                d.content().forEach(n -> out.add(n.toBusiness()));
                if (d.last() == null || d.last()) {
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
            List<Draft> d = data(restClient.get().uri(b -> b.path("/internal/onboarding")
                    .queryParamIfPresent("status", Optional.ofNullable(status)).build()).retrieve().body(DRAFTS));
            return d == null ? new ArrayList<>() : new ArrayList<>(d);
        } catch (Exception e) {
            warn("GET /internal/onboarding", e);
            return List.of();
        }
    }

    /**
     * Onboarding drafts per status (IN_PROGRESS, READY_FOR_HANDOVER, …) from the paged board's counts (GET /onboarding?page=0&size=1),
     * without downloading every draft. Null when the counts could not be read (the caller may fall back to {@link #onboarding}).
     */
    public Map<String, Long> onboardingCounts() {
        try {
            DraftCounts d = data(restClient.get().uri(b -> b.path("/onboarding").queryParam("page", 0).queryParam("size", 1).build())
                    .retrieve().body(DRAFT_COUNTS));
            if (d == null || d.counts() == null) {
                return null;
            }
            Map<String, Long> out = new LinkedHashMap<>();
            d.counts().forEach((k, v) -> out.put(k, v == null ? 0L : v));
            return out;
        } catch (Exception e) {
            warn("GET /onboarding?page=0&size=1", e);
            return null;
        }
    }

    /**
     * PUT /internal/businesses/{id}/fee {feeModel, feeValue, feeAddendumRef} (tenant). The addendum reference is null at the platform
     * default (fee lock). Returns false when it could not be written.
     */
    public boolean setFee(String businessId, String feeModel, BigDecimal feeValue, String feeAddendumRef) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("feeModel", feeModel);
        body.put("feeValue", feeValue);
        body.put("feeAddendumRef", feeAddendumRef);
        try {
            restClient.put().uri("/internal/businesses/{id}/fee", businessId).body(body)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            warn("PUT /internal/businesses/" + businessId + "/fee", e);
            return false;
        }
    }

    /** PUT /internal/businesses/{id}/marketer {marketerId} (null clears it). Returns false when it could not be written. */
    public boolean setMarketer(String businessId, String marketerId) {
        try {
            Map<String, String> body = new java.util.HashMap<>();
            body.put("marketerId", marketerId);
            restClient.put().uri("/internal/businesses/{id}/marketer", businessId).body(body).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            warn("PUT /internal/businesses/" + businessId + "/marketer", e);
            return false;
        }
    }

    // ---- suspension (docs/suspension.md) ----------------------------------------------------------------------

    private static final ParameterizedTypeReference<ApiResponse<SuspensionDetail>> SUSPENSION = new ParameterizedTypeReference<>() {
    };

    /** tenant's BusinessResponse again, the suspension fields. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuspensionDetail(String id, String name, String stage, Instant suspendedAt, String suspensionReason, String suspensionType,
                                   boolean suspensionShowReason, java.time.LocalDate suspensionNoticeSentOn, String stageBeforeSuspension,
                                   String suspendedByName) {
        public boolean suspended() {
            return "SUSPENDED".equals(stage);
        }
    }

    /** The business's stage and suspension fields. Throws when tenant-service can't be asked: a suspension is never decided on a guess. */
    public SuspensionDetail suspensionDetail(String businessId) {
        try {
            SuspensionDetail d = data(restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(SUSPENSION));
            if (d == null) {
                throw new com.hospio.common.exception.ResourceNotFoundException("Business not found");
            }
            return d;
        } catch (HttpClientErrorException.NotFound e) {
            throw new com.hospio.common.exception.ResourceNotFoundException("Business not found");
        } catch (org.springframework.web.client.RestClientException e) {
            throw new com.hospio.common.exception.ExternalServiceException("Could not read the business right now. Try again", e);
        }
    }

    /** PUT /internal/businesses/{id}/suspension: tenant's own refusal (already suspended, not live) is passed on as it reads. */
    public SuspensionDetail suspend(String businessId, String reasonType, String reason, boolean showReason, java.time.LocalDate noticeSentOn,
                                    String actorId, String actorName) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reasonType", reasonType);
        body.put("reason", reason);
        body.put("showReason", showReason);
        body.put("noticeSentOn", noticeSentOn == null ? null : noticeSentOn.toString());
        body.put("actorId", actorId);
        body.put("actorName", actorName);
        return change(() -> data(restClient.put().uri("/internal/businesses/{id}/suspension", businessId).body(body).retrieve().body(SUSPENSION)));
    }

    /** DELETE /internal/businesses/{id}/suspension: back to the stage it had. */
    public SuspensionDetail reactivate(String businessId) {
        return change(() -> data(restClient.delete().uri("/internal/businesses/{id}/suspension", businessId).retrieve().body(SUSPENSION)));
    }

    private static SuspensionDetail change(java.util.function.Supplier<SuspensionDetail> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            String message = null;
            try {
                ApiResponse<?> body = e.getResponseBodyAs(new ParameterizedTypeReference<ApiResponse<Object>>() {
                });
                message = body == null ? null : body.message();
            } catch (RuntimeException ignored) {
                // fall through to the plain message
            }
            throw new com.hospio.common.exception.BadRequestException(message == null ? "The business could not be changed" : message);
        } catch (org.springframework.web.client.RestClientException e) {
            throw new com.hospio.common.exception.ExternalServiceException("Could not change the business right now. Nothing was changed; try again", e);
        }
    }

    static <T> T data(ApiResponse<T> body) {
        return body == null ? null : body.data();
    }

    private static void warn(String call, Exception e) {
        if (e instanceof HttpClientErrorException.NotFound) {
            log.warn("tenant-service has no {} yet; answering empty", call);
        } else {
            log.warn("tenant-service {} failed: {}", call, e.getMessage());
        }
    }
}
