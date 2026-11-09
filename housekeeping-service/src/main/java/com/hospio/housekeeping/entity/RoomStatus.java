package com.hospio.housekeeping.entity;

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

/** Latest cleanliness of a room for the room board: DIRTY, CLEAN, INSPECTED. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "room_status")
public class RoomStatus extends TenantEntity {

    public enum Cleanliness { DIRTY, CLEAN, INSPECTED }

    @Column(name = "room_id", nullable = false, unique = true, length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Cleanliness status = Cleanliness.DIRTY;

    @Column(name = "changed_by", length = 36)
    private String changedBy;

    @Column(length = 300)
    private String note;
}
