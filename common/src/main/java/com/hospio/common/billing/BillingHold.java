package com.hospio.common.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.BillingHoldException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
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
 * Whether a business is restricted for an unpaid Hospio360 invoice (docs/plan-monthly-invoices.md). platform-service decides; the services
 * that take new sales, bookings and card/transfer payments ask here before they do, and refuse with {@link BillingHoldException} (423).
 * Answers are cached {@value #TTL_MS} ms per business, so a payment lifts the restriction within a minute. When platform-service can't
 * be reached the business is treated as not restricted: an outage never stops a hotel trading.
 */
@Slf4j
@Component
public class BillingHold {

    static final long TTL_MS = 30_000L;
    private static final ParameterizedTypeReference<ApiResponse<Hold>> HOLD = new ParameterizedTypeReference<>() {
    };

    /** platform's answer: restricted or not, the invoice and the sentence the screens show. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hold(boolean restricted, String invoiceNumber, String message) {
        public static final Hold NONE = new Hold(false, null, null);
    }

    private record Cached(Hold hold, long until) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private RestClient platform;

    @Autowired
    public void configure(@Value("${services.platform.url:}") String platformUrl, @Value("${services.internal.api-key:}") String internalApiKey,
                          JwtUtil jwtUtil) {
        this.platform = StringUtils.hasText(platformUrl) ? ServiceClients.system(platformUrl, jwtUtil, internalApiKey) : null;
    }

    /** The business's restriction (cached); NONE when not restricted or unknown. */
    public Hold of(String businessId) {
        if (businessId == null || platform == null) {
            return Hold.NONE;
        }
        long now = System.currentTimeMillis();
        Cached c = cache.get(businessId);
        if (c != null && c.until() > now) {
            return c.hold();
        }
        Hold h = fetch(businessId);
        cache.put(businessId, new Cached(h, now + TTL_MS));
        return h;
    }

    private Hold fetch(String businessId) {
        try {
            ApiResponse<Hold> body = platform.get().uri("/internal/billing/holds/{id}", businessId).retrieve().body(HOLD);
            return body == null || body.data() == null ? Hold.NONE : body.data();
        } catch (Exception e) {
            log.warn("Billing hold for business {} not checked (platform-service): {}", businessId, e.getMessage());
            return Hold.NONE;
        }
    }

    /**
     * Refuses {@code what} (e.g. "New sales") for the signed-in person's business while it is restricted. Platform staff are never
     * refused (they act for Hospio360).
     */
    public void require(String what) {
        HospioUserDetails user = CurrentUser.get().orElse(null);
        if (user == null || user.isPlatform()) {
            return;
        }
        requireFor(user.getBusinessId(), what);
    }

    public void requireFor(String businessId, String what) {
        Hold h = of(businessId);
        if (h.restricted()) {
            throw new BillingHoldException(what + " are paused: " + (StringUtils.hasText(h.message()) ? h.message()
                    : "the Hospio360 invoice is unpaid. They resume as soon as the owner pays it."));
        }
    }

    /** Forget a cached answer (tests, or platform-service lifting the restriction itself). */
    public void forget(String businessId) {
        cache.remove(businessId);
    }
}
