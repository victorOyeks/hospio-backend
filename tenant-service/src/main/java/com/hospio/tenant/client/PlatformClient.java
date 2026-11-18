package com.hospio.tenant.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * What tenant asks platform-service: which marketer a referral code belongs to (self-serve sign-up with ?ref=), the platform default
 * fee and the change log for a fee set off the default (fee lock).
 */
@Slf4j
@Component
public class PlatformClient {

    private static final ParameterizedTypeReference<ApiResponse<ReferralCode>> CODE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<FeeDefault>> FEE_DEFAULT = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public PlatformClient(@Value("${services.platform.url}") String platformUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(platformUrl, jwtUtil, internalApiKey);
    }

    /** platform's GET /internal/marketers/referral-codes/{code}: the profile, its status and whether it may refer (ACTIVE, agreement accepted). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferralCode(String profileId, String code, String fullName, String status, boolean canRefer) {
    }

    /** The marketer behind {@code code}; empty when no profile has it. Throws when platform-service can't be asked. */
    public Optional<ReferralCode> referralCode(String code) {
        try {
            ApiResponse<ReferralCode> body = restClient.get().uri("/internal/marketers/referral-codes/{code}", code).retrieve().body(CODE);
            return Optional.ofNullable(body == null ? null : body.data());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new ExternalServiceException("Could not check the referral code right now", e);
        } catch (Exception e) {
            log.warn("Referral code {} not checked: {}", code, e.getMessage());
            throw new ExternalServiceException("Could not check the referral code right now", e);
        }
    }

    /** platform's GET /internal/fees/default: the fee every business pays unless a signed addendum says otherwise. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FeeDefault(String model, java.math.BigDecimal revenuePct) {
    }

    /** The platform default share of daily sales (fees.revenuePct); 1.5 when platform-service can't be asked. */
    public java.math.BigDecimal feeDefaultPct() {
        try {
            ApiResponse<FeeDefault> body = restClient.get().uri("/internal/fees/default").retrieve().body(FEE_DEFAULT);
            if (body != null && body.data() != null && body.data().revenuePct() != null) {
                return body.data().revenuePct();
            }
        } catch (Exception e) {
            log.warn("Platform default fee not read, using {}%: {}", com.hospio.common.billing.FeeLock.FALLBACK_PCT, e.getMessage());
        }
        return com.hospio.common.billing.FeeLock.FALLBACK_PCT;
    }

    /**
     * platform's POST /internal/changes/fee: one line in the change log (area "Fee") for a fee set off the default with its addendum,
     * or put back to the default. Throws when it could not be recorded, so the fee change is rolled back with it.
     */
    public void recordFee(String businessId, java.math.BigDecimal feeValue, String feeAddendumRef, java.math.BigDecimal previousValue,
                          java.math.BigDecimal defaultPct, String actorId, String actorName) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("businessId", businessId);
        body.put("feeValue", feeValue);
        body.put("feeAddendumRef", feeAddendumRef);
        body.put("previousValue", previousValue);
        body.put("defaultPct", defaultPct);
        body.put("actorId", actorId);
        body.put("actorName", actorName);
        try {
            restClient.post().uri("/internal/changes/fee").body(body).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Fee change for business {} not recorded: {}", businessId, e.getMessage());
            throw new ExternalServiceException("Could not record the fee change in the audit trail right now. Nothing was saved; try again", e);
        }
    }
}
