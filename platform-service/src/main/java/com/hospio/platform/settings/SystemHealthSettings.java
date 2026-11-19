package com.hospio.platform.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The system health group (Settings → System health, docs/system-health.md): when the server alerts the super admins.
 * {@code diskWarnPct}/{@code diskCriticalPct}: a disk this full (default 85% and 95%). {@code memoryPct} + {@code swapPct}: memory
 * this full while swap is this full, for {@code memoryMinutes}. {@code serviceDownMinutes}: a service down this long.
 * {@code certDays}: a certificate this close to expiry. {@code kafkaLag} messages waiting with no progress for
 * {@code kafkaStuckMinutes}. {@code dbConnectionsPct} of Postgres' connections in use.
 *
 * <p>{@code alertsOn}: the master switch (System health → Alerts). Off: nothing is emailed or sent to the bell; the page and the
 * dashboard still show every problem. Never saved (null) = the environment's default: off on dev and staging, on in production
 * ({@code SystemHealthConfig#alertsDefault}). {@code emailAlerts} and {@code bellAlerts} pick the channels while it is on.
 * A null field in a save means "not sent, keep it".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemHealthSettings(Integer diskWarnPct, Integer diskCriticalPct, Integer memoryPct, Integer swapPct, Integer memoryMinutes,
                                   Integer serviceDownMinutes, Integer certDays, Integer kafkaLag, Integer kafkaStuckMinutes,
                                   Integer dbConnectionsPct, Boolean emailAlerts, Boolean alertsOn, Boolean bellAlerts) {

    /** alertsOn null: the environment decides until someone saves the switch. */
    public static final SystemHealthSettings DEFAULTS = new SystemHealthSettings(85, 95, 90, 75, 15, 2, 14, 500, 10, 80, true, null, true);

    public SystemHealthSettings(Integer diskWarnPct, Integer diskCriticalPct, Integer memoryPct, Integer swapPct, Integer memoryMinutes,
                                Integer serviceDownMinutes, Integer certDays, Integer kafkaLag, Integer kafkaStuckMinutes, Integer dbConnectionsPct,
                                Boolean emailAlerts) {
        this(diskWarnPct, diskCriticalPct, memoryPct, swapPct, memoryMinutes, serviceDownMinutes, certDays, kafkaLag, kafkaStuckMinutes,
                dbConnectionsPct, emailAlerts, null, null);
    }
}
