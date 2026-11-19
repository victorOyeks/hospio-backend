package com.hospio.platform.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The security group. {@code auditRetentionDays}: how long audit logs (the activity log and Changes by Hospio360) are kept before
 * they are deleted automatically: 7 days since 19 Nov 2026 (docs/data-retention.md); the admin portal can set more, never less than 7. A null field in a save means "not sent, keep it".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecuritySettings(Boolean require2Step, Integer sessionHours, Boolean adminIpAllowlist, Integer auditRetentionDays) {

    public static final SecuritySettings DEFAULTS = new SecuritySettings(false, 12, false, 7);
}
