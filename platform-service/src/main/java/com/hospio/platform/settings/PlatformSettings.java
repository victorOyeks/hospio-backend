package com.hospio.platform.settings;

/** Every settings group, saved values laid over the defaults: {@code {general: {...}, fees: {...}, security: {...}, system: {...}}}. */
public record PlatformSettings(GeneralSettings general, FeeSettings fees, SecuritySettings security, SystemHealthSettings system) {
}
