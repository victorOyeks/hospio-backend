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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "messages")
public class Message extends TenantEntity {

    public enum Direction { IN, OUT }

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column(name = "sender_id", length = 36)
    private String senderId;

    @Column(name = "sender_name", length = 150)
    private String senderName;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "SENT";

    @Column(name = "provider_ref", length = 100)
    private String providerRef;
}
