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
import java.time.LocalDate;

/**
 * One amount collected towards a device package: TRANSACTION (the device part of a daily debit), INVOICE (a monthly instalment or
 * data plan invoice), ONE_TIME or MANUAL (recorded by an admin). Unique per package, source and reference, so a repeat never counts twice.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_payments")
public class DevicePayment extends TenantEntity {

    public static final String TRANSACTION = "TRANSACTION";
    public static final String INVOICE = "INVOICE";
    public static final String ONE_TIME = "ONE_TIME";
    public static final String MANUAL = "MANUAL";

    @Column(name = "package_id", nullable = false, length = 36)
    private String packageId;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 80)
    private String reference;

    @Column(name = "daily_fee_id", length = 36)
    private String dailyFeeId;

    @Column(name = "invoice_id", length = 36)
    private String invoiceId;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(length = 300)
    private String note;

    @Column(name = "recorded_by", length = 36)
    private String recordedBy;

    @Column(name = "recorded_by_name", length = 150)
    private String recordedByName;
}
