package com.hospio.payment.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A card/transfer terminal from the POS provider, assigned to one or more roles. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pos_terminals")
public class PosTerminal extends TenantEntity {

    public enum Status { ONLINE, OFFLINE, RETIRED }

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "terminal_ref", nullable = false, length = 100)
    private String terminalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.ONLINE;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
