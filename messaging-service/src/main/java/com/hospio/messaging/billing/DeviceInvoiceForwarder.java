package com.hospio.messaging.billing;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Paystack sends every webhook to messaging-service (/webhooks/paystack). A device invoice payment (reference "DEV-…",
 * docs/plan-device-packages.md) or a Hospio360 monthly invoice payment ("HIN-…", docs/plan-monthly-invoices.md) belongs to
 * platform-service: it is passed on there to be checked with Paystack and settled.
 */
@Slf4j
@Component
public class DeviceInvoiceForwarder {

    private final RestClient platform;

    public DeviceInvoiceForwarder(@Value("${services.platform.url}") String platformUrl, JwtUtil jwtUtil,
                                  @Value("${services.internal.api-key}") String internalApiKey) {
        this.platform = ServiceClients.system(platformUrl, jwtUtil, internalApiKey);
    }

    public void forward(String reference) {
        String path = reference != null && reference.startsWith("HIN-") ? "/internal/billing/paystack-confirm" : "/internal/devices/paystack-confirm";
        try {
            platform.post().uri(u -> u.path(path).queryParam("reference", reference).build()).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // The owner's return to the app confirms it too; a missed webhook only delays the update.
            log.warn("Invoice payment {} not passed to platform-service: {}", reference, e.getMessage());
        }
    }
}
