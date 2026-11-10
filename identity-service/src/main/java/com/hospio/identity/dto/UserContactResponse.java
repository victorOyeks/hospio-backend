package com.hospio.identity.dto;

import java.util.List;

/**
 * Service-to-service lookup: name and contact only, nothing auth-related. {@code emailPreferences}: the email categories
 * the person wants (true) or turned off (false), docs/profile.md; a producer checks it before sending a categorised email.
 */
public record UserContactResponse(String id, String fullName, String email, String phone, String businessId,
                                  String status, List<String> roles, java.util.Map<String, Boolean> emailPreferences) {
}
