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

/** A thread with a guest (WhatsApp, SMS, email) or between staff. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversations")
public class Conversation extends TenantEntity {

    public enum Kind { GUEST, STAFF }

    public enum Status { OPEN, CLOSED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "guest_id", length = 36)
    private String guestId;

    @Column(name = "guest_name", length = 150)
    private String guestName;

    @Column(name = "guest_contact")
    private String guestContact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(length = 200)
    private String subject;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Column(name = "assigned_to_name", length = 150)
    private String assignedToName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;
}
