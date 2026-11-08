package com.hospio.tenant.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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
@Table(name = "hotels")
public class Hotel extends BaseEntity {

    /** PAUSED: not taking bookings for a while (renovation, low season); CLOSED: gone for good. */
    public enum Status { ACTIVE, PAUSED, CLOSED }

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(nullable = false)
    private String name;

    private String city;
    private String address;
    private String phone;
    private String email;
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "room_count", nullable = false)
    @lombok.Builder.Default
    private int roomCount = 0;

    /** "HH:mm", as the apps show it. */
    @Column(name = "check_in_time", nullable = false, length = 5)
    @lombok.Builder.Default
    private String checkInTime = "14:00";

    @Column(name = "check_out_time", nullable = false, length = 5)
    @lombok.Builder.Default
    private String checkOutTime = "12:00";

    @Column(name = "star_rating")
    private Integer starRating;

    @Column(name = "manager_user_id", length = 36)
    private String managerUserId;
}
