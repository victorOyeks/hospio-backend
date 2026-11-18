package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One line of a monthly invoice. Sections: PLATFORM (one per location: sales × rate = fee; the days are the daily fees billed on the
 * invoice), MESSAGES (one per location's guest-message invoice), DEVICES (device instalments, data plan months, the from-transactions
 * device share), CARRIED (an earlier month under the minimum), ADJUSTMENT (a credit note, negative, or a charge added by Hospio360).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "monthly_invoice_lines")
public class MonthlyInvoiceLine extends TenantEntity {

    public static final String PLATFORM = "PLATFORM";
    public static final String MESSAGES = "MESSAGES";
    public static final String DEVICES = "DEVICES";
    public static final String CARRIED = "CARRIED";
    public static final String ADJUSTMENT = "ADJUSTMENT";

    /** source_type values. */
    public static final String DAILY_FEES = "DAILY_FEES";
    public static final String DEVICE_SHARE = "DEVICE_SHARE";
    public static final String COMM_INVOICE = "COMM_INVOICE";
    public static final String DEVICE_INVOICE = "DEVICE_INVOICE";
    public static final String MONTHLY_INVOICE = "MONTHLY_INVOICE";

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(nullable = false, length = 20)
    private String section;

    @Column(name = "hotel_name", length = 150)
    private String hotelName;

    @Column(nullable = false, length = 300)
    private String label;

    @Column(length = 500)
    private String detail;

    /** PLATFORM: the location's sales for the month; DEVICES share: the sales the share is worked on. */
    @Column(name = "base_amount")
    private BigDecimal baseAmount;

    /** The % applied to {@code baseAmount}; null when the days had different rates. */
    @Column
    private BigDecimal rate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id", length = 36)
    private String sourceId;

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;
}
