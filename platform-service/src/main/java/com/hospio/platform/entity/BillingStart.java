package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The day a business's platform fee starts (docs/plan-monthly-invoices.md, "Charges start on"). No row, or no date, means free: its fee
 * days are worked out for visibility as FREE and never invoiced. Days on or after the date are billed as usual. Devices are billed either
 * way. Platform-only table; set by money admins (audit-logged in Changes by Hospio360, area "Billing start").
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "billing_starts")
public class BillingStart extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36, unique = true)
    private String businessId;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "charges_start_on")
    private LocalDate chargesStartOn;

    @Column(name = "set_by", length = 36)
    private String setBy;

    @Column(name = "set_by_name", length = 150)
    private String setByName;

    @Column(length = 500)
    private String reason;

    /** The start date the money admins were last reminded of (7 days before); a new date reminds again. */
    @Column(name = "reminded_for")
    private LocalDate remindedFor;
}
