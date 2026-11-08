package com.hospio.identity.dto;

import java.util.List;

/** Service-to-service lookup: name and contact only, nothing auth-related. */
public record UserContactResponse(String id, String fullName, String email, String phone, String businessId,
                                  String status, List<String> roles) {
}
