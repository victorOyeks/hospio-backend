package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The platform's fee settings (provider fee %, H.M.S share %) for settlements. Safe defaults when unreachable. */
@Slf4j
@Component
public class PlatformClient {

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
            JsonNode body = restClient.get().uri("/internal/settings/fees").retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode() || d.isNull()) {
                return FeeSettings.defaults();
            }
            FeeSettings def = FeeSettings.defaults();
            return new FeeSettings(number(d, "providerFeePct", def.providerFeePct()), number(d, "hmsSharePct", def.hmsSharePct()));
        } catch (Exception e) {
            log.warn("Could not read platform fee settings ({}); settling with the defaults 0.5% / 1.5%", e.getMessage());
            return FeeSettings.defaults();
        }
    }

    private static BigDecimal number(JsonNode d, String key, BigDecimal fallback) {
        JsonNode n = d.path(key);
        if (n.isMissingNode() || n.isNull()) {
            return fallback;
        }
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
