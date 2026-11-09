package com.hospio.inventory.entity;

import com.hospio.common.entity.TenantEntity;
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

/** An order to a supplier. {@code lines} is JSON: [{itemId, name, quantity, unitCost}]. Over the limit it needs approval. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends TenantEntity {

    public enum Status { DRAFT, PENDING_APPROVAL, SENT, PARTIAL, RECEIVED, CLOSED, CANCELLED }

    @Column(nullable = false, length = 20)
    private String number;

    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

    @Column(name = "store_id", length = 36)
    private String storeId;

    @Column(nullable = false, columnDefinition = "text")
    private String lines;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "approval_id", unique = true, length = 36)
    private String approvalId;

    @Column(name = "expected_on")
    private LocalDate expectedOn;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "approver_level", length = 20)
    private String approverLevel;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @Column(name = "sent_to", length = 255)
    private String sentTo;

    @Column(name = "received_value", nullable = false)
    @lombok.Builder.Default
    private BigDecimal receivedValue = BigDecimal.ZERO;
}
