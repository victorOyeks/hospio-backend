package com.hospio.messaging.billing;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Paystack sends every webhook to messaging-service (/webhooks/paystack). A device invoice payment (reference "DEV-…",
 * docs/plan-device-packages.md) belongs to platform-service: it is passed on there to be checked with Paystack and settled.
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
        try {
            platform.post().uri(u -> u.path("/internal/devices/paystack-confirm").queryParam("reference", reference).build()).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // The owner's return to the app confirms it too; a missed webhook only delays the update.
            log.warn("Device invoice payment {} not passed to platform-service: {}", reference, e.getMessage());
        }
    }
}
