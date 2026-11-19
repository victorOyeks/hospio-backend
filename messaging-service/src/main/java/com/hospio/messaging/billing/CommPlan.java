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

    /** Either channel is on (kept for older readers): always {@code emailOn || whatsappOn}, set through {@link #setChannels}. */
    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean enabled = false;

    /** Guest emails go (and are charged) only while this is on. */
    @Column(name = "email_on", nullable = false)
    @lombok.Builder.Default
    private boolean emailOn = false;

    /** Guest WhatsApp messages go (and are charged) only while this is on. */
    @Column(name = "whatsapp_on", nullable = false)
    @lombok.Builder.Default
    private boolean whatsappOn = false;

    /** Switches either channel (null leaves it) and keeps {@code enabled} as "either is on". */
    public void setChannels(Boolean email, Boolean whatsapp) {
        if (email != null) {
            this.emailOn = email;
        }
        if (whatsapp != null) {
            this.whatsappOn = whatsapp;
        }
        this.enabled = this.emailOn || this.whatsappOn;
    }

    /** Whether messages on {@code channel} may go: EMAIL by the email switch, anything else (WhatsApp) by the WhatsApp switch. */
    public boolean on(String channel) {
        return "EMAIL".equalsIgnoreCase(channel == null ? "" : channel) ? emailOn : whatsappOn;
    }

    @Column(name = "email_price")
    private BigDecimal emailPrice;


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
