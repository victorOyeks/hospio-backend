package com.hospio.messaging.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Whether a business is in its free period (docs/plan-monthly-invoices.md, "Charges start on"): platform-service decides; guest messages
 * sent then are priced at NGN 0 and never invoiced. Cached {@value #TTL_MS} ms per business. When platform-service can't be reached the
 * last answer is used, else the message is priced as usual (an outage never makes messages free by accident; the amounts are small).
 */
@Slf4j
@Component
public class ChargesStartClient {

    static final long TTL_MS = 60_000L;
    private static final ParameterizedTypeReference<ApiResponse<Start>> START = new ParameterizedTypeReference<>() {
    };

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Start(String businessId, LocalDate chargesStartOn, boolean freeToday) {
    }

    private record Cached(Start start, long until) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private RestClient platform;

    @Autowired
    public void configure(@Value("${services.platform.url:}") String platformUrl, @Value("${services.internal.api-key:}") String internalApiKey,
                          JwtUtil jwtUtil) {
        this.platform = StringUtils.hasText(platformUrl) ? ServiceClients.system(platformUrl, jwtUtil, internalApiKey) : null;
    }

    /** The business's start date and whether today is free; null when not known (no business, or platform-service never answered). */
    public Start of(String businessId) {
        if (!StringUtils.hasText(businessId) || platform == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Cached c = cache.get(businessId);
        if (c != null && c.until() > now) {
            return c.start();
        }
        try {
            ApiResponse<Start> body = platform.get().uri("/internal/billing/charges-start/{id}", businessId).retrieve().body(START);
            Start s = body == null ? null : body.data();
            if (s != null) {
                cache.put(businessId, new Cached(s, now + TTL_MS));
            }
            return s;
        } catch (Exception e) {
            log.warn("Charges start for business {} not checked (platform-service): {}", businessId, e.getMessage());
            return c == null ? null : c.start();
        }
    }

    /** Free today (before the charges start, or no date). False when it can't be told. */
    public boolean freeToday(String businessId) {
        Start s = of(businessId);
        return s != null && s.freeToday();
    }
}
