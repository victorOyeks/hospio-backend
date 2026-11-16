package com.hospio.platform.client;

import com.hospio.common.http.HttpTimeouts;
import com.hospio.common.integrations.settings.IntegrationProvider;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.InternalApiKeyInterceptor;
import com.hospio.common.security.client.SystemAuthInterceptor;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Settings · Integrations → Test: the check runs in the service that uses the provider (messaging-service for email, SMS, WhatsApp and
 * Paystack; payment-service for POS and the fee debit), with the settings in force there. A provider's own timeouts are up to 20
 * seconds, so this waits longer than the usual internal call.
 */
@Slf4j
@Component
public class IntegrationTestClient {

    private static final ParameterizedTypeReference<ApiResponse<IntegrationTestResult>> RESULT = new ParameterizedTypeReference<>() {
    };

    private final RestClient messaging;
    private final RestClient payment;

    public IntegrationTestClient(@Value("${services.messaging.url}") String messagingUrl, @Value("${services.payment.url}") String paymentUrl,
                                 JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.messaging = client(messagingUrl, jwtUtil, internalApiKey);
        this.payment = client(paymentUrl, jwtUtil, internalApiKey);
    }

    IntegrationTestClient(RestClient messaging, RestClient payment) {
        this.messaging = messaging;
        this.payment = payment;
    }

    public IntegrationTestResult test(IntegrationProvider p) {
        boolean inMessaging = p.owner() == IntegrationProvider.Owner.MESSAGING;
        String service = inMessaging ? "messaging-service" : "payment-service";
        try {
            ApiResponse<IntegrationTestResult> body = (inMessaging ? messaging : payment).post()
                    .uri("/internal/integrations/{p}/test", p.name()).retrieve().body(RESULT);
            if (body != null && body.data() != null) {
                return body.data();
            }
            return IntegrationTestResult.failed(service + " gave no answer, so the test couldn't run");
        } catch (Exception e) {
            log.warn("Integration test {} through {} failed: {}", p, service, e.getMessage());
            return IntegrationTestResult.failed(service + " couldn't be reached, so the test couldn't run. Try again in a minute");
        }
    }

    private static RestClient client(String baseUrl, JwtUtil jwtUtil, String internalApiKey) {
        return HttpTimeouts.builder(Duration.ofSeconds(5), Duration.ofSeconds(45)).baseUrl(baseUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }
}
