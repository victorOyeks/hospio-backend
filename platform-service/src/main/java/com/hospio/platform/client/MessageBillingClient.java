package com.hospio.platform.client;

import com.hospio.common.billing.MessageBillingDtos.Link;
import com.hospio.common.billing.MessageBillingDtos.ToBill;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * messaging-service's guest-message invoices, billed on the monthly invoice (docs/plan-monthly-invoices.md): which are waiting to be
 * billed, putting them on an invoice, settling them when it is paid and freeing them when it is voided.
 */
@Slf4j
@Component
public class MessageBillingClient {

    private static final ParameterizedTypeReference<ApiResponse<List<ToBill>>> LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    public MessageBillingClient(@Value("${services.messaging.url}") String messagingUrl, JwtUtil jwtUtil,
                                @Value("${services.internal.api-key}") String internalApiKey) {
        this.client = ServiceClients.system(messagingUrl, jwtUtil, internalApiKey);
    }

    /** Throws when messaging can't be reached: an invoice is never issued without its message charges (the run tries again). */
    public List<ToBill> toBill(String businessId, String month) {
        ApiResponse<List<ToBill>> body = client.get().uri(b -> b.path("/internal/billing/messages/to-bill").queryParam("businessId", businessId)
                .queryParam("month", month).build()).retrieve().body(LIST);
        return body == null || body.data() == null ? List.of() : body.data();
    }

    public void link(Link l) {
        post("/internal/billing/messages/link", l);
    }

    public void settle(Link l) {
        post("/internal/billing/messages/settle", l);
    }

    public void release(Link l) {
        post("/internal/billing/messages/release", l);
    }

    private void post(String path, Link l) {
        try {
            client.post().uri(path).body(l).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("messaging {} for monthly invoice {} failed: {}", path, l.monthlyInvoiceNumber(), e.getMessage());
        }
    }
}
