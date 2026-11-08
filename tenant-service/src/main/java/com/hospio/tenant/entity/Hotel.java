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

    public enum Status { ACTIVE, CLOSED }

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

    @Column(name = "room_count", nullable = false)
    @lombok.Builder.Default
    private int roomCount = 0;
}
