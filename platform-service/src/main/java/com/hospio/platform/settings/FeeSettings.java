package com.hospio.platform.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * The fees group. Percentages are percents (2 means 2%), amounts are naira. Read through
 * {@link com.hospio.platform.service.PlatformSettingsService#fees()} every field is set: a value never saved, or saved in a form that
 * can't be read, is its default. A null field in a save means "not sent, keep it".
 *
 * <p>Monthly invoice (docs/plan-monthly-invoices.md): {@code invoiceVatPct} is added on top of the month's charges when above 0 (default 0:
 * no VAT is charged; for the accountant to decide); a total under {@code invoiceMinimum} (NGN 1,000) is carried forward to next month.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeeSettings(String model, BigDecimal perRoom, BigDecimal revenuePct, Integer trialDays, Integer dueDay, Integer graceDays,
                          BigDecimal marketerSharePct, Integer marketerMonths, BigDecimal whtPct, BigDecimal providerFeePct,
                          BigDecimal hmsSharePct, BigDecimal marketerMinPayout, BigDecimal invoiceVatPct, BigDecimal invoiceMinimum) {

    public static final FeeSettings DEFAULTS = new FeeSettings("PERCENT_OF_SALES", new BigDecimal("1500"), new BigDecimal("1.5"), 30, 5, 10,
            new BigDecimal("20"), 12, new BigDecimal("5"), new BigDecimal("0.5"), new BigDecimal("1.5"), new BigDecimal("5000"), BigDecimal.ZERO,
            new BigDecimal("1000"));

    public static FeeSettings defaults() {
        return DEFAULTS;
    }
}
