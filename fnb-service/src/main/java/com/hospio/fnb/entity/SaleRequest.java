package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Something the floor may not do alone, waiting for a manager: a void (an item already sent, or a whole sale) or a discount over
 * the limit / a complimentary item. It is a platform approval too ({@code approvalId}); the decision comes back and is applied here.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sale_requests")
public class SaleRequest extends TenantEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    /** Approved, but the sale had moved on (paid, closed, the item gone): nothing was changed. */
    public static final String NOT_APPLIED = "NOT_APPLIED";

    public enum Kind { VOID_LINE, VOID_SALE, DISCOUNT_LINE, DISCOUNT_BILL, COMP_LINE, COMP_BILL }

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;

    @Column(name = "line_id", length = 36)
    private String lineId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Column(name = "pct")
    private BigDecimal pct;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "worth")
    private BigDecimal worth;

    @Column(name = "summary", length = 300)
    private String summary;

    @Column(name = "reason", nullable = false, length = 300)
    private String reason;

    @Column(name = "status", nullable = false, length = 12)
    @lombok.Builder.Default
    private String status = PENDING;

    @Column(name = "approval_id", nullable = false, unique = true, length = 36)
    private String approvalId;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_by_name", length = 150)
    private String decidedByName;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
