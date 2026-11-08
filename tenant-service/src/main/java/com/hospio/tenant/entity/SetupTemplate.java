package com.hospio.tenant.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A platform-owned Hotel setup template (e.g. "City hotel · 3 star") applied to a new hotel. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "setup_templates")
public class SetupTemplate extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String settings;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}
