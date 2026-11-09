package com.hospio.folio.config;

import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.util.Money;
import java.math.BigDecimal;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A hotel's own limits in Hotel setup (APPROVAL_LIMITS) are the source of truth; the {@code hospio.approvals.*}
 * properties apply only when hotel-config can't be reached.
 */
@Getter
@Component
public class ApprovalLimits {

    @Value("${hospio.approvals.manager-discount-limit:50000}")
    private BigDecimal managerDiscountLimit;

    @Value("${hospio.approvals.org-credit-owner-limit:1000000}")
    private BigDecimal orgCreditOwnerLimit;

    @Getter(lombok.AccessLevel.NONE)
    private HotelSettingsReader settings;

    @Autowired(required = false)
    void setSettings(HotelSettingsReader settings) {
        this.settings = settings;
    }

    /** The largest discount a manager may give on a folio worth {@code folioTotal}: discountPct of it from Hotel setup, else the fixed fallback. */
    public BigDecimal discountLimit(String hotelId, BigDecimal folioTotal) {
        if (settings != null) {
            var pct = settings.section(hotelId, SetupCatalogue.APPROVAL_LIMITS).map(n -> n.path("discountPct")).filter(n -> n.isNumber());
            if (pct.isPresent()) {
                return Money.percent(folioTotal, pct.get().decimalValue());
            }
        }
        return managerDiscountLimit;
    }
}
