package com.hospio.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The platform's fee settings (provider fee %, Hospio360 share %) for settlements. Safe defaults when unreachable. */
@Slf4j
@Component
public class PlatformClient {

    /**
     * The two settings of platform-service's fees group this client reads. The group is a settings map whose values an admin types,
     * so each is read as its text (a number or a string) and falls back on its own when it isn't a number.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FeesGroup(String providerFeePct, String hmsSharePct) {
    }

    private static final ParameterizedTypeReference<ApiResponse<FeesGroup>> FEES = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public PlatformClient(@Value("${services.platform.url}") String platformUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(platformUrl, jwtUtil, internalApiKey);
    }

    public record FeeSettings(BigDecimal providerFeePct, BigDecimal hmsSharePct) {
        public static FeeSettings defaults() {
            return new FeeSettings(new BigDecimal("0.5"), new BigDecimal("1.5"));
        }
    }

    /** GET /internal/settings/fees → the fees group; providerFeePct and hmsSharePct are what settlements need. */
    public FeeSettings feeSettings() {
        try {
            ApiResponse<FeesGroup> body = restClient.get().uri("/internal/settings/fees").retrieve().body(FEES);
            FeesGroup d = body == null ? null : body.data();
            if (d == null) {
                return FeeSettings.defaults();
            }
            FeeSettings def = FeeSettings.defaults();
            return new FeeSettings(number(d.providerFeePct(), def.providerFeePct()), number(d.hmsSharePct(), def.hmsSharePct()));
        } catch (Exception e) {
            log.warn("Could not read platform fee settings ({}); settling with the defaults 0.5% / 1.5%", e.getMessage());
            return FeeSettings.defaults();
        }
    }

    private static BigDecimal number(String value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
