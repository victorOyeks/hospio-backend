package com.hospio.platform.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The security group. {@code auditRetentionDays}: how long the activity log is kept (six months by default since 2026-11-15; the
 * admin portal can set more or less, at least 30 days). A null field in a save means "not sent, keep it".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecuritySettings(Boolean require2Step, Integer sessionHours, Boolean adminIpAllowlist, Integer auditRetentionDays) {

    public static final SecuritySettings DEFAULTS = new SecuritySettings(false, 12, false, 183);
}
