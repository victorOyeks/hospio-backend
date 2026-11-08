package com.hospio.messaging.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A WhatsApp, SMS or email waiting to go out. The worker sends and retries with back-off. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "outbound_messages")
public class OutboundMessage extends TenantEntity {

    public enum Status { QUEUED, SENT, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String recipient;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(length = 40)
    private String kind;

    @Column(length = 80)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.QUEUED;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    @lombok.Builder.Default
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(name = "sent_at")
    private Instant sentAt;
}
