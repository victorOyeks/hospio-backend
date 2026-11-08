package com.hospio.hotelconfig.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Apply a setup template to the hotel in X-Hotel-Id: by id (fetched from tenant-service) or inline.
 * Settings are {"sections": {SECTION: {key: value}}, "lists": {LIST: [{code, label, sortOrder?}]}}, or
 * the older flat {SECTION: {key: value}}.
 */
public record ApplyTemplateRequest(String templateId, JsonNode settings) {
}
