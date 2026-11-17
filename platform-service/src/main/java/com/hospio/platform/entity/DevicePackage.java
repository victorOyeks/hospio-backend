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
import java.time.Instant;
import java.time.LocalDate;

/**
 * The devices and internet Hospio360 supplies to one hotel (docs/plan-device-packages.md). {@code total} is the one-off items;
 * {@code paid} what has been collected towards it; {@code dataPlanMonthly} the recurring data plan, invoiced monthly and never
 * "paid off". Status: DRAFT (being put together) → INSTALLED (collection starts) → PAID_OFF; or CANCELLED. Payment option:
 * OWN_DEVICES, TRANSACTION (deviceRate % of daily sales), MONTHLY (total over {@code months} instalments), ONE_TIME.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_packages")
public class DevicePackage extends TenantEntity {

    public static final String DRAFT = "DRAFT";
    public static final String INSTALLED = "INSTALLED";
    public static final String PAID_OFF = "PAID_OFF";
    public static final String CANCELLED = "CANCELLED";

    public static final String OWN_DEVICES = "OWN_DEVICES";
    public static final String TRANSACTION = "TRANSACTION";
    public static final String MONTHLY = "MONTHLY";
    public static final String ONE_TIME = "ONE_TIME";

    @Column(name = "hotel_name", length = 150)
    private String hotelName;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "payment_option", nullable = false, length = 20)
    private String paymentOption;

    @Column(name = "device_rate")
    private BigDecimal deviceRate;

    private Integer months;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal paid = BigDecimal.ZERO;

    @Column(name = "data_plan_monthly", nullable = false)
    @lombok.Builder.Default
    private BigDecimal dataPlanMonthly = BigDecimal.ZERO;

    @Column(length = 500)
    private String notes;

    @Column(name = "agreed_by", length = 36)
    private String agreedBy;

    @Column(name = "agreed_by_name", length = 150)
    private String agreedByName;

    @Column(name = "agreed_at")
    private Instant agreedAt;

    @Column(name = "installed_at")
    private LocalDate installedAt;

    @Column(name = "paid_off_at")
    private Instant paidOffAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Data plan billed from this day (the install day when null); restarting moves it to the next due day. */
    @Column(name = "data_plan_from")
    private LocalDate dataPlanFrom;

    /** Data plan stopped: no invoice due on or after this day (null: running). */
    @Column(name = "data_plan_stopped_on")
    private LocalDate dataPlanStoppedOn;

    @Column(name = "data_plan_changed_by_name", length = 150)
    private String dataPlanChangedByName;

    @Column(name = "data_plan_changed_at")
    private Instant dataPlanChangedAt;

    /** The real provider subscription for the hotel's router, renewed by Hospio360 through this day. */
    @Column(name = "data_plan_renewed_through")
    private LocalDate dataPlanRenewedThrough;

    @Column(name = "data_plan_renewed_by_name", length = 150)
    private String dataPlanRenewedByName;

    @Column(name = "data_plan_renewed_at")
    private Instant dataPlanRenewedAt;

    /** A data plan that is billed (has a price, not stopped). */
    public boolean dataPlanRunning() {
        return dataPlanMonthly != null && dataPlanMonthly.signum() > 0 && dataPlanStoppedOn == null;
    }

    /** Whether the data plan invoice due on {@code due} is billed: on or after it started, before it stopped. */
    public boolean dataPlanBills(LocalDate due) {
        return dataPlanMonthly != null && dataPlanMonthly.signum() > 0 && (dataPlanFrom == null || !due.isBefore(dataPlanFrom))
                && (dataPlanStoppedOn == null || due.isBefore(dataPlanStoppedOn));
    }

    /** The first data plan due day (install day + whole months) on or after {@code from}; null without an installation. */
    public LocalDate dataPlanDueOnOrAfter(LocalDate from) {
        if (installedAt == null) {
            return null;
        }
        for (int m = 0; m < 1200; m++) {
            LocalDate due = installedAt.plusMonths(m);
            if (!due.isBefore(from)) {
                return due;
            }
        }
        return null;
    }

    /** The next data plan invoice due from {@code today} on while running; null when stopped or none. */
    public LocalDate nextDataPlanDue(LocalDate today) {
        if (!dataPlanRunning()) {
            return null;
        }
        LocalDate from = dataPlanFrom != null && dataPlanFrom.isAfter(today) ? dataPlanFrom : today;
        return dataPlanDueOnOrAfter(from);
    }

    /** What is left of the one-off total. */
    public BigDecimal remaining() {
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    /** Collecting towards the one-off total: installed, not paid off, something left. */
    public boolean collecting() {
        return INSTALLED.equals(status) && remaining().signum() > 0;
    }
}
