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

/** An in-app notification for one user or for everyone with a role at the hotel. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notifications")
public class Notification extends TenantEntity {

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(length = 30)
    private String role;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    private String link;

    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "read_at")
    private Instant readAt;
}
