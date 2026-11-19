package com.hospio.common.suspension;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Instant;
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
 * Whether a business is suspended (docs/suspension.md). tenant-service owns the answer (the business's stage); every service asks here:
 * {@link SuspensionFilter} for each request made by someone at that business, and the background jobs before they work for it. Answers
 * are cached {@value #TTL_MS} ms per business, so a suspension, or a reactivation, reaches every service within a minute.
 *
 * <p>When tenant-service can't be reached the business is treated as NOT suspended (the last known answer is not kept past its 30 s).
 * A suspension is rare and deliberate; an outage of one service must never lock every hotel out of its front desk. The cost is that a
 * suspended business could get in for as long as tenant-service is down, which is the smaller harm.
 */
@Slf4j
@Component
public class BusinessSuspension {

    static final long TTL_MS = 30_000L;
    public static final String CODE = "BUSINESS_SUSPENDED";
    public static final String HEADER = "X-Business-Suspended";
    public static final String CONTACT = "hello@oyeksglobal.com";
    private static final ParameterizedTypeReference<ApiResponse<Status>> STATUS = new ParameterizedTypeReference<>() {
    };

    /**
     * tenant's answer. {@code reason} is the admin's note only when they chose to show it to the business; {@code reasonType} is
     * UNPAID_INVOICE or OTHER.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(boolean suspended, Instant since, String reasonType, String reason) {
        public static final Status NONE = new Status(false, null, null, null);
    }

    private record Cached(Status status, long until) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private RestClient tenant;

    @Autowired
    public void configure(@Value("${services.tenant.url:}") String tenantUrl, @Value("${services.internal.api-key:}") String internalApiKey,
                          JwtUtil jwtUtil) {
        this.tenant = StringUtils.hasText(tenantUrl) ? ServiceClients.system(tenantUrl, jwtUtil, internalApiKey) : null;
    }

    /** The business's suspension (cached); NONE when not suspended or unknown. */
    public Status of(String businessId) {
        if (businessId == null || tenant == null) {
            return Status.NONE;
        }
        long now = System.currentTimeMillis();
        Cached c = cache.get(businessId);
        if (c != null && c.until() > now) {
            return c.status();
        }
        Status s = fetch(businessId);
        cache.put(businessId, new Cached(s, now + TTL_MS));
        return s;
    }

    /** For jobs: true when the business is suspended and nothing should be sent, opened or run for it. */
    public boolean isSuspended(String businessId) {
        return of(businessId).suspended();
    }

    private Status fetch(String businessId) {
        try {
            ApiResponse<Status> body = tenant.get().uri("/internal/businesses/{id}/suspension", businessId).retrieve().body(STATUS);
            return body == null || body.data() == null ? Status.NONE : body.data();
        } catch (Exception e) {
            log.warn("Suspension of business {} not checked (tenant-service): {}", businessId, e.getMessage());
            return Status.NONE;
        }
    }

    /** Forget a cached answer (tests, or the service that just changed it). */
    public void forget(String businessId) {
        cache.remove(businessId);
    }
}
