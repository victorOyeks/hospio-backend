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

/**
 * One provider's settings saved in the admin portal (Settings · Integrations): the settings record as JSON, encrypted (SecretBox), and
 * the copy the screen shows, secrets masked. Platform-only table.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "integration_settings")
public class IntegrationSetting extends BaseEntity {

    /** BREVO, SMTP, INFOBIP… (IntegrationProvider). */
    @Column(nullable = false, length = 40, unique = true)
    private String provider;

    @Column(name = "sealed_values", nullable = false, columnDefinition = "text")
    private String sealedValues;

    /** JSON object key → shown value (a secret masked); the keys are the saved fields. */
    @Column(name = "masked_values", nullable = false, columnDefinition = "text")
    private String maskedValues;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;
}
