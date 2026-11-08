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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lost_found_items")
public class LostFoundItem extends TenantEntity {

    public enum Status { FOUND, RETURNED, DISPOSED }

    @Column(nullable = false, length = 300)
    private String description;

    @Column(name = "found_where", length = 150)
    private String foundWhere;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "found_by", length = 36)
    private String foundBy;

    @Column(name = "found_at", nullable = false)
    @lombok.Builder.Default
    private Instant foundAt = Instant.now();

    @Column(name = "photo_key")
    private String photoKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.FOUND;

    @Column(name = "returned_to", length = 150)
    private String returnedTo;

    @Column(name = "returned_at")
    private Instant returnedAt;
}
