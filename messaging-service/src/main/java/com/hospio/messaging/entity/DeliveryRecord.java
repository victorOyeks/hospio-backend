package com.hospio.messaging.entity;

import com.hospio.common.entity.BaseEntity;
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

/** One email, SMS or WhatsApp the platform sent (or tried to): to whom, what about, and what the provider answered. docs/email.md. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "deliveries")
public class DeliveryRecord extends BaseEntity {

    /** QUEUED until the sender has an answer; NOT_CONNECTED for SMS and WhatsApp; DISABLED when email is switched off. */
    /** DELIVERED: the SMS or WhatsApp reached the phone (Infobip's delivery report); a rejected one goes back to FAILED. */
    public enum Status { QUEUED, SENT, DELIVERED, FAILED, NOT_CONNECTED, DISABLED }

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "hotel_id", length = 36)
    private String hotelId;

    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(length = 300)
    private String subject;

    @Column(length = 40)
    private String kind;

    @Column(length = 80)
    private String ref;

    @Column(name = "from_name", length = 200)
    private String fromName;

    @Column(name = "reply_to")
    private String replyTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int attempts = 0;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(length = 500)
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
