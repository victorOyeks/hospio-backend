package com.hospio.hotelconfig.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One Hotel setup value. {@code value} is JSON text so a setting can be a string, number, boolean, list or object. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "settings")
public class Setting extends TenantEntity {

    @Column(nullable = false, length = 40)
    private String section;

    @Column(nullable = false, length = 80)
    private String key;

    @Column(columnDefinition = "text")
    private String value;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
