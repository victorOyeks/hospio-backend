package com.hospio.platform.client;

import com.hospio.common.billing.FeeDebitDtos.DebitRequest;
import com.hospio.common.billing.FeeDebitDtos.DebitResult;
import com.hospio.common.billing.FeeDebitDtos.ProviderState;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * payment-service holds each hotel's provider keys (encrypted), so it runs the fee debit: which providers can debit (configured on
 * the server, and the hotel's keys present), the debit itself, its status, and pausing POS push for a hotel.
 */
@Slf4j
@Component
public class FeeDebitClient {

    private static final ParameterizedTypeReference<ApiResponse<List<ProviderState>>> STATES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<DebitResult>> RESULT = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    public FeeDebitClient(@Value("${services.payment.url}") String paymentUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.client = ServiceClients.system(paymentUrl, jwtUtil, internalApiKey);
    }

    public List<ProviderState> providers(String hotelId) {
        try {
            ApiResponse<List<ProviderState>> body = client.get().uri(b -> b.path("/internal/fee-debit/providers").queryParam("hotelId", hotelId).build())
                    .header("X-Hotel-Id", hotelId).retrieve().body(STATES);
            if (body != null && body.data() != null) {
                return body.data();
            }
        } catch (Exception e) {
            log.warn("payment-service fee-debit providers for {} failed: {}", hotelId, e.getMessage());
        }
        String why = "payment-service didn't answer";
        return List.of(new ProviderState("MONIEPOINT", false, false, why), new ProviderState("OPAY", false, false, why));
    }

    /** Asks for {@code amount} from the hotel's account; a transport error is PENDING (the reference makes a repeat safe). */
    public DebitResult debit(String hotelId, String provider, BigDecimal amount, String reference, String narration) {
        try {
            ApiResponse<DebitResult> body = client.post().uri("/internal/fee-debit").header("X-Hotel-Id", hotelId)
                    .body(new DebitRequest(hotelId, provider, amount, reference, narration)).retrieve().body(RESULT);
            return body == null || body.data() == null ? DebitResult.pending("no answer") : body.data();
        } catch (Exception e) {
            log.warn("Fee debit {} for {} not answered: {}", reference, hotelId, e.getMessage());
            return DebitResult.pending("payment-service didn't answer: " + e.getMessage());
        }
    }

    public DebitResult status(String hotelId, String provider, String reference) {
        try {
            ApiResponse<DebitResult> body = client.get().uri(b -> b.path("/internal/fee-debit/{ref}").queryParam("hotelId", hotelId)
                    .queryParam("provider", provider).build(reference)).header("X-Hotel-Id", hotelId).retrieve().body(RESULT);
            return body == null || body.data() == null ? DebitResult.pending("no answer") : body.data();
        } catch (Exception e) {
            return DebitResult.pending("payment-service didn't answer: " + e.getMessage());
        }
    }

    public void pausePos(String businessId, String hotelId, boolean paused) {
        try {
            client.post().uri(b -> b.path("/internal/pos/pause").queryParam("businessId", businessId).queryParam("hotelId", hotelId)
                            .queryParam("paused", paused).build())
                    .header("X-Hotel-Id", hotelId).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("POS pause={} for {} not applied: {}", paused, hotelId, e.getMessage());
        }
    }
}
