package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One line on a hotel application's timeline: a note, a status change or something the website added. Never edited or deleted. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hotel_application_notes")
public class HotelApplicationNote extends BaseEntity {

    public static final String NOTE = "NOTE";
    public static final String STATUS = "STATUS";
    public static final String SYSTEM = "SYSTEM";

    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "author_id", length = 36)
    private String authorId;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;
}
