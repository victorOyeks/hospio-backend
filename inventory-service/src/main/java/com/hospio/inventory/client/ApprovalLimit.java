package com.hospio.inventory.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The purchase-order approval limit: Hotel setup → Approval limits → managerExpenseLimit (cached 60 s), else the service property. */
@Component
public class ApprovalLimit {

    private final HotelSettingsReader settings;
    private final BigDecimal fallback;

    public ApprovalLimit(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String key,
                         @Value("${hospio.approvals.manager-spend-limit:500000}") BigDecimal fallback) {
        this.settings = new HotelSettingsReader(url, jwtUtil, key);
        this.fallback = fallback;
    }

    protected ApprovalLimit(BigDecimal fixed) {
        this.settings = null;
        this.fallback = fixed;
    }

    public static ApprovalLimit fixed(BigDecimal limit) {
        return new ApprovalLimit(limit);
    }

    public BigDecimal forHotel(String hotelId) {
        if (settings == null) {
            return fallback;
        }
        return settings.section(hotelId, SetupCatalogue.APPROVAL_LIMITS)
                .map(n -> n.path("managerExpenseLimit").isNumber() ? n.path("managerExpenseLimit").decimalValue() : fallback)
                .orElse(fallback);
    }
}
