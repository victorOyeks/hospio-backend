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

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "found_by_name", length = 150)
    private String foundByName;

    /** Colour, brand, anything that helps the guest describe it. */
    @Column(length = 500)
    private String details;

    @Column(name = "returned_by", length = 36)
    private String returnedBy;

    @Column(name = "returned_by_name", length = 150)
    private String returnedByName;

    /** The ID the owner showed (e.g. "NIN ending 4471"). */
    @Column(name = "return_id_note", length = 200)
    private String returnIdNote;

    @Column(name = "return_how", length = 40)
    private String returnHow;

    @Column(name = "disposed_at")
    private Instant disposedAt;

    @Column(name = "disposed_by_name", length = 150)
    private String disposedByName;

    @Column(name = "dispose_reason", length = 300)
    private String disposeReason;

    @Column(name = "front_office_told_at")
    private Instant frontOfficeToldAt;
}
