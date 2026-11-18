package com.hospio.hotelconfig.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.setup.SetupTemplateSettings;

/**
 * Apply a setup template to the hotel in X-Hotel-Id: by id (fetched from tenant-service) or inline.
 * Settings are {"sections": {SECTION: {key: value}}, "lists": {LIST: [{code, label, sortOrder?}]}}, or
 * the older flat {SECTION: {key: value}}. {@code builtIn: "VENUE"} applies the built-in "Lounge / bar / restaurant" template
 * (docs/plan-venue-type.md, phase 3): its values, its lists, and the hotel-only default list entries are hidden.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplyTemplateRequest(String templateId, SetupTemplateSettings settings, String builtIn) {

    public ApplyTemplateRequest(String templateId, SetupTemplateSettings settings) {
        this(templateId, settings, null);
    }
}
