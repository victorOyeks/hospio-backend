package com.hospio.platform.settings;

/** Every settings group, saved values laid over the defaults: {@code {general: {...}, fees: {...}, security: {...}}}. */
public record PlatformSettings(GeneralSettings general, FeeSettings fees, SecuritySettings security) {
}
