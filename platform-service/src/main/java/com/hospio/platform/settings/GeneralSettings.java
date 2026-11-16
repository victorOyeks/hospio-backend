package com.hospio.platform.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The general group: who the platform is and how people reach support. A null field in a save means "not sent, keep it". */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeneralSettings(String company, String supportEmail, String supportWhatsApp, String website, String timezone) {

    public static final GeneralSettings DEFAULTS = new GeneralSettings("Hospio360", "support@hospio360.com", "+2348000000000",
            "https://hospio360.com", "Africa/Lagos");
}
