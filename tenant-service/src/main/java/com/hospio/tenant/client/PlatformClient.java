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

/** What tenant asks platform-service: which marketer a referral code belongs to (self-serve sign-up with ?ref=). */
@Slf4j
@Component
public class PlatformClient {

    private static final ParameterizedTypeReference<ApiResponse<ReferralCode>> CODE = new ParameterizedTypeReference<>() {
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
}
