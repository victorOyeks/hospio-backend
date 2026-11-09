package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.ApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fee_statements")
public class FeeStatement extends TenantEntity {

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "base_amount", nullable = false)
    private BigDecimal baseAmount;

    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount;

    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** DUE, PART_PAID, PAID or OVERDUE (set by the nightly job once dueOn + grace days have passed). */
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "DUE";

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "business_name", length = 150)
    private String businessName;

    /** JSON array of {hotelId, hotelName, base, fee}. */
    @Column(columnDefinition = "text")
    private String lines;

    @Column(name = "sent_at")
    private Instant sentAt;

    public BigDecimal outstanding() {
        return feeAmount == null ? BigDecimal.ZERO : feeAmount.subtract(paidAmount == null ? BigDecimal.ZERO : paidAmount);
    }
}
