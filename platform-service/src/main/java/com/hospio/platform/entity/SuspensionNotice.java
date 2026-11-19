package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The 7 days' notice sent to an owner before a business is suspended for unpaid invoices (docs/suspension.md, Terms 7.3): the day it
 * went, the day the suspension may start and the invoices it named. Platform-only table.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "suspension_notices")
public class SuspensionNotice extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "sent_on", nullable = false)
    private LocalDate sentOn;

    @Column(name = "suspend_on", nullable = false)
    private LocalDate suspendOn;

    @Column(name = "invoice_numbers", nullable = false, length = 1000)
    private String invoiceNumbers;

    @Column(nullable = false)
    private BigDecimal total;

    private String recipient;

    /** EMAILED, NO_EMAIL. */
    @Column(nullable = false, length = 30)
    private String outcome;

    @Column(name = "sent_by", length = 36)
    private String sentBy;

    @Column(name = "sent_by_name", length = 150)
    private String sentByName;
}
