package com.hospio.messaging.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The platform's default prices per message (one row, id DEFAULT); a hotel's own prices override them. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "comm_prices")
public class CommPrices {

    @Id
    private String id;

    @Column(name = "email_price", nullable = false)
    private BigDecimal emailPrice;

    @Column(name = "sms_price", nullable = false)
    private BigDecimal smsPrice;

    @Column(name = "whatsapp_price", nullable = false)
    private BigDecimal whatsappPrice;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
