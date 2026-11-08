package com.hospio.reservation.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "guests")
public class Guest extends TenantEntity {

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    private String phone;
    private String email;

    @Column(name = "id_type", length = 30)
    private String idType;

    @Column(name = "id_number", length = 60)
    private String idNumber;

    private String nationality;

    @Column(length = 500)
    private String preferences;

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean blacklisted = false;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int stays = 0;
}
