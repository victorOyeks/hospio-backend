package com.hospio.common.billing;

import com.hospio.common.setup.SetupSection;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Hospio360's fee passed to guests (docs/plan-device-packages.md): when the owner chooses it (Hotel setup → Payments), every sale
 * carries "Service charge (x%)" at the business's fee rate, so the guest pays it and the hotel keeps its full price. The charge is
 * never counted in the daily sales the fee is worked out on, and the device fee is never passed on this way.
 */
public final class GuestServiceCharge {

    /** The folio line category and order field name for the charge. */
    public static final String CATEGORY = "SERVICE_CHARGE";
    public static final String SOURCE = "SERVICE_CHARGE";

    private GuestServiceCharge() {
    }

    /** The rate to charge guests at this hotel: the fee rate when the owner passes the fee on, else 0. */
    public static BigDecimal rate(SetupSection.Payments p) {
        if (p == null || !Boolean.TRUE.equals(p.guestPaysPlatformFee()) || p.platformFeePct() == null || p.platformFeePct().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return p.platformFeePct();
    }

    /** {@code rate} % of {@code amount}, to the kobo (half up); 0 on nothing or a negative amount. */
    public static BigDecimal on(BigDecimal amount, BigDecimal rate) {
        if (amount == null || amount.signum() <= 0 || rate == null || rate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** "Service charge (1.5%)". */
    public static String label(BigDecimal rate) {
        return "Service charge (" + (rate == null ? "0" : rate.stripTrailingZeros().toPlainString()) + "%)";
    }
}
