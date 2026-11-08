package com.hospio.finance.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
@Table(name = "shifts")
public class Shift extends TenantEntity {

    public enum Status { SCHEDULED, SWAPPED, COVERED, CANCELLED }

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(nullable = false, length = 30)
    private String role;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "starts_at", nullable = false)
    private LocalTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.SCHEDULED;
}
