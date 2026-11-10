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

    /** Staff threads: who started it (the owner, a manager). */
    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    /** The person (audience PERSON, id in assigned_to) or "Managers" (audience MANAGERS: the hotel's managers). */
    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Column(length = 20)
    private String audience;

    @Column(name = "broadcast_id", length = 36)
    private String broadcastId;

    @Column(name = "last_preview", length = 300)
    private String lastPreview;

    @Column(name = "creator_read_at")
    private Instant creatorReadAt;

    @Column(name = "recipient_read_at")
    private Instant recipientReadAt;

    /** Guest conversations: the booking they are about, and the stay's details the templates fill in. */
    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "booking_ref", length = 40)
    private String bookingRef;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "room_type", length = 100)
    private String roomType;

    @Column(name = "check_in")
    private java.time.LocalDate checkIn;

    @Column(name = "check_out")
    private java.time.LocalDate checkOut;

    @Column(name = "guest_email")
    private String guestEmail;

    @Column(name = "guest_phone", length = 40)
    private String guestPhone;

    /** IN when the guest wrote last (waiting for an answer), OUT when the hotel did. */
    @Column(name = "last_direction", length = 10)
    private String lastDirection;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by_name", length = 150)
    private String doneByName;

    /** What would have gone to the guest (email, SMS, WhatsApp), echoed on staging; never stored. */
    @jakarta.persistence.Transient
    private java.util.List<com.hospio.common.delivery.Delivery> deliveries;
}
