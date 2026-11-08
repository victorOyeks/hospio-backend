package com.hospio.maintenance.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "repair_updates")
public class RepairUpdate extends TenantEntity {

    @Column(name = "repair_id", nullable = false, length = 36)
    private String repairId;

    @Column(nullable = false, length = 1000)
    private String note;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "by_user", length = 36)
    private String byUser;

    @Column(name = "by_name", length = 150)
    private String byName;
}
