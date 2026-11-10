package com.hospio.finance.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One approved payment in the ledger. Today's money, the P&L and Match a payment read these. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "revenue_entries")
public class RevenueEntry extends TenantEntity {

    @Column(name = "payment_id", nullable = false, unique = true, length = 36)
    private String paymentId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(length = 30)
    private String role;

    @Column(name = "folio_id", length = 36)
    private String folioId;

    /** The kitchen or bar order this payment settles, when it is for one. */
    @Column(name = "order_id", length = 36)
    private String orderId;

    /** For an order: the desk whose income it is (BAR or KITCHEN, from the order's outlet), whoever took it. {@code role} is the payer's desk (their till). */
    @Column(length = 30)
    private String desk;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean matched = true;

    /** Cash taken while no till was open for the role: it reaches the ledger but no till, and the manager is shown it. */
    @Column(name = "unattached_cash", nullable = false)
    @lombok.Builder.Default
    private boolean unattachedCash = false;

    /**
     * The desk this money counts as income for: an outlet sale is that outlet's income whoever took the payment (a manager
     * collecting a bar sale is Bar income); anything else is the desk that took it. Cash still lands in the payer's till.
     */
    public String incomeDesk() {
        return incomeDesk(desk, role);
    }

    public static String incomeDesk(String desk, String role) {
        if (desk != null && !desk.isBlank()) {
            return desk;
        }
        return role == null ? "OTHER" : role;
    }
}
