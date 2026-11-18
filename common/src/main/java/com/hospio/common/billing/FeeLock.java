package com.hospio.common.billing;

import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * The platform fee lock (docs/plan-daily-fees.md, "Fee lock"): every business pays the platform default share of daily sales
 * ({@code fees.revenuePct}, 1.5 % unless changed in platform settings). A different rate, or another model, needs the reference of a
 * signed addendum, which is stored with the fee and recorded in the platform's change log. The rule lives here so tenant-service
 * (business create / edit, onboarding's fee step) and platform-service (fee rules) refuse the same things with the same words.
 */
public final class FeeLock {

    /** The default when platform-service can't be asked: FeeSettings.DEFAULTS.revenuePct. */
    public static final BigDecimal FALLBACK_PCT = new BigDecimal("1.5");
    public static final String DEFAULT_MODEL = "PERCENT_OF_SALES";
    public static final int MAX_REF = 120;

    private FeeLock() {
    }

    /** True when {@code model}/{@code value} is the platform default (a null model counts as the default model). */
    public static boolean isDefault(String model, BigDecimal value, BigDecimal defaultPct) {
        BigDecimal pct = defaultPct == null ? FALLBACK_PCT : defaultPct;
        boolean sameModel = model == null || DEFAULT_MODEL.equals(model.toUpperCase(Locale.ROOT));
        return sameModel && value != null && value.compareTo(pct) == 0;
    }

    /**
     * Checks a fee being set and returns the addendum reference to store with it: null for the platform default (any reference sent
     * is dropped, the default needs none), the trimmed reference otherwise. Throws 400 when a different rate comes without one.
     */
    public static String addendumFor(String model, BigDecimal value, String addendumRef, BigDecimal defaultPct) {
        BigDecimal pct = defaultPct == null ? FALLBACK_PCT : defaultPct;
        if (isDefault(model, value, pct)) {
            return null;
        }
        String ref = addendumRef == null ? "" : addendumRef.trim();
        if (ref.isEmpty()) {
            throw new BadRequestException("The platform fee is " + percent(pct) + "% for everyone. A different rate needs a signed addendum: enter its reference");
        }
        if (ref.length() > MAX_REF) {
            throw new BadRequestException("The addendum reference can be at most " + MAX_REF + " characters");
        }
        return ref;
    }

    /** 1.50 → "1.5", 2 → "2". */
    public static String percent(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** The change-log line for a fee set: "Fee set to 2% of daily sales (addendum ADD-2026-014)" or back to the default. */
    public static String describe(BigDecimal value, String addendumRef, BigDecimal defaultPct) {
        if (addendumRef == null || addendumRef.isBlank()) {
            return "Fee back to the platform default " + percent(defaultPct == null ? FALLBACK_PCT : defaultPct) + "% of daily sales";
        }
        return "Fee set to " + percent(value) + "% of daily sales (addendum " + addendumRef.trim() + ")";
    }
}
