package com.hospio.platform.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The security group. {@code auditRetentionDays}: how long audit logs (the activity log and Changes by Hospio360) are kept before
 * they are deleted automatically: 7 days since 19 Nov 2026 (docs/data-retention.md); the admin portal can set more, never less than 7. A null field in a save means "not sent, keep it".
 * <p>
 * Enforced by identity-service for Hospio360 staff (SUPER_ADMIN, SUPPORT, FINANCE_ADMIN; not marketers, not hotel accounts), read
 * through GET /internal/settings/security (docs/sign-in.md, "Admin security settings"): {@code require2Step} (a staff member
 * without 2-step gets a session that can only set it up) and {@code sessionHours} (4, 12 or 24: how long a staff sign-in lasts).
 * {@code adminIpAllowlist} is NOT implemented: there is no IP list, nothing reads it, and the admin portal no longer shows it
 * (21 Nov 2026). It stays in the record so saved rows and older clients keep working.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecuritySettings(Boolean require2Step, Integer sessionHours, Boolean adminIpAllowlist, Integer auditRetentionDays) {

    public static final SecuritySettings DEFAULTS = new SecuritySettings(false, 12, false, 7);

    /** The session lengths the admin portal offers (hours). */
    public static final java.util.List<Integer> SESSION_HOURS = java.util.List.of(4, 12, 24);
}
