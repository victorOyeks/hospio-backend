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

/** A message template from Hotel setup → Front desk, e.g. BOOKING_CONFIRMED, STAY_SUMMARY, RECEIPT. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "templates")
public class Template extends TenantEntity {

    @Column(nullable = false, length = 60)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}
