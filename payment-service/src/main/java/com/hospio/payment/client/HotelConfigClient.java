package com.hospio.payment.client;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.setup.SetupSection;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Hotel setup → Payments: which methods are allowed and the receipt rule. Defaults when unreachable. */
@Slf4j
@Component
public class HotelConfigClient {

    private static final ParameterizedTypeReference<ApiResponse<SetupSection.Payments>> PAYMENTS = new ParameterizedTypeReference<>() {
    };

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
        return settings.section(hotelId, SetupSection.ApprovalLimits.class).map(a -> new ApprovalLimits(a.managerExpenseLimit(),
                Boolean.TRUE.equals(a.refundsNeedOwner()))).orElse(new ApprovalLimits(null, false));
    }

    /** OUTLETS from Hotel setup (cached 60 s): whether waiters take payment, and whether they may take cash (into the cashier's till). */
    public record WaiterRules(boolean takePayment, boolean cash) {
    }

    public WaiterRules waiterRules(String hotelId) {
        return settings.section(hotelId, SetupSection.Outlets.class).map(o -> new WaiterRules(Boolean.TRUE.equals(o.waitersTakePayment()),
                Boolean.TRUE.equals(o.waiterCash()))).orElse(new WaiterRules(false, false));
    }

    public record PaymentSettings(boolean allowCash, boolean allowCard, boolean allowTransfer, String receipts) {
        public static PaymentSettings defaults() {
            return new PaymentSettings(true, true, true, "ASK");
        }

        /** The hotel's PAYMENTS section, each key it lacks taking the default above. */
        static PaymentSettings of(SetupSection.Payments p) {
            if (SetupCatalogue.isEmpty(p)) {
                return defaults();
            }
            return new PaymentSettings(p.allowCash() == null || p.allowCash(), p.allowCard() == null || p.allowCard(),
                    p.allowTransfer() == null || p.allowTransfer(), p.receipts() == null ? "ASK" : p.receipts());
        }
    }

    /** Read fresh on every payment (not cached), as a payment must follow a method the manager has just switched off. */
    public PaymentSettings paymentSettings(String hotelId) {
        try {
            ApiResponse<SetupSection.Payments> body = restClient.get().uri("/internal/settings/PAYMENTS?hotelId={h}", hotelId).retrieve().body(PAYMENTS);
            return PaymentSettings.of(body == null ? null : body.data());
        } catch (Exception e) {
            log.warn("Could not read payment settings for hotel {}: {}", hotelId, e.getMessage());
            return PaymentSettings.defaults();
        }
    }
}
