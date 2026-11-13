package com.hospio.messaging.billing;

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
 * A hotel's guest-messages service (docs/guest-message-billing.md): switched on or not, its own prices (null: the platform
 * default), and whether it is suspended for an unpaid invoice.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comm_plans")
public class CommPlan extends TenantEntity {

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean enabled = false;

    @Column(name = "email_price")
    private BigDecimal emailPrice;

    @Column(name = "sms_price")
    private BigDecimal smsPrice;

    @Column(name = "whatsapp_price")
    private BigDecimal whatsappPrice;

    @Column(name = "changed_by", length = 150)
    private String changedBy;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_reason", length = 300)
    private String suspendedReason;
}
