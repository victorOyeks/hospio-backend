package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Hotel setup → Payments: which methods are allowed and the receipt rule. Defaults when unreachable. */
@Slf4j
@Component
public class HotelConfigClient {

    private final RestClient restClient;
    private final HotelSettingsReader settings;

    public HotelConfigClient(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
        this.settings = new HotelSettingsReader(url, jwtUtil, internalApiKey);
    }

    /** APPROVAL_LIMITS from Hotel setup (cached 60 s); null fields when hotel-config can't be reached so the caller falls back to its property. */
    public record ApprovalLimits(BigDecimal managerExpenseLimit, boolean refundsNeedOwner) {
    }

    public ApprovalLimits approvalLimits(String hotelId) {
        return settings.section(hotelId, SetupCatalogue.APPROVAL_LIMITS).map(n -> new ApprovalLimits(
                n.path("managerExpenseLimit").isNumber() ? n.path("managerExpenseLimit").decimalValue() : null,
                n.path("refundsNeedOwner").asBoolean(false))).orElse(new ApprovalLimits(null, false));
    }

    public record PaymentSettings(boolean allowCash, boolean allowCard, boolean allowTransfer, String receipts) {
        public static PaymentSettings defaults() {
            return new PaymentSettings(true, true, true, "ASK");
        }
    }

    public PaymentSettings paymentSettings(String hotelId) {
        try {
            JsonNode body = restClient.get().uri("/internal/settings/PAYMENTS?hotelId={h}", hotelId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode() || data.isEmpty()) {
                return PaymentSettings.defaults();
            }
            return new PaymentSettings(data.path("allowCash").asBoolean(true), data.path("allowCard").asBoolean(true),
                    data.path("allowTransfer").asBoolean(true), data.path("receipts").asText("ASK"));
        } catch (Exception e) {
            log.warn("Could not read payment settings for hotel {}: {}", hotelId, e.getMessage());
            return PaymentSettings.defaults();
        }
    }
}
