package com.hospio.platform.client;

import com.hospio.common.http.HttpTimeouts;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.InternalApiKeyInterceptor;
import com.hospio.common.security.client.SystemAuthInterceptor;
import com.hospio.platform.dto.IntegrationDtos.ServiceCheck;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The admin portal's connection checks that other services make (GET /internal/integrations/status): messaging-service for
 * WhatsApp, email and SMS, payment-service for the POS provider. Short timeouts: the page waits for every row. Throws when the
 * service can't be asked; {@link com.hospio.platform.service.IntegrationsService} turns that into the row's reason.
 */
@Component
public class ConnectionCheckClient {

    private static final ParameterizedTypeReference<ApiResponse<List<ServiceCheck>>> LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<ServiceCheck>> ONE = new ParameterizedTypeReference<>() {
    };

    private final RestClient messaging;
    private final RestClient payment;

    @Autowired
    public ConnectionCheckClient(@Value("${services.messaging.url}") String messagingUrl, @Value("${services.payment.url}") String paymentUrl,
                                 JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey,
                                 @Value("${hospio.integrations.status-timeout-ms:5000}") long timeoutMs) {
        this.messaging = client(messagingUrl, jwtUtil, internalApiKey, Duration.ofMillis(timeoutMs));
        this.payment = client(paymentUrl, jwtUtil, internalApiKey, Duration.ofMillis(timeoutMs));
    }

    ConnectionCheckClient(RestClient messaging, RestClient payment) {
        this.messaging = messaging;
        this.payment = payment;
    }

    /** WhatsApp, email and SMS as messaging-service sees them. */
    public List<ServiceCheck> messaging() {
        ApiResponse<List<ServiceCheck>> body = messaging.get().uri("/internal/integrations/status").retrieve().body(LIST);
        return body == null || body.data() == null ? List.of() : body.data();
    }

    /** The POS provider as payment-service sees it; null when it gave no answer. */
    public ServiceCheck payment() {
        ApiResponse<ServiceCheck> body = payment.get().uri("/internal/integrations/status").retrieve().body(ONE);
        return body == null ? null : body.data();
    }

    private static RestClient client(String baseUrl, JwtUtil jwtUtil, String internalApiKey, Duration read) {
        return HttpTimeouts.builder(Duration.ofSeconds(2), read).baseUrl(baseUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }
}
