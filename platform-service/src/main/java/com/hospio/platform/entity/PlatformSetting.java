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

/** One platform setting: a key in a group (general, fees, security) with its value as JSON text. Platform-only table. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "platform_settings")
public class PlatformSetting extends BaseEntity {

    @Column(name = "group_name", nullable = false, length = 40)
    private String groupName;

    @Column(name = "setting_key", nullable = false, length = 60)
    private String settingKey;

    /** JSON: a string, number, boolean, object or array. */
    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
