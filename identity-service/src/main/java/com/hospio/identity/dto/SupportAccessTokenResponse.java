package com.hospio.identity.dto;

import java.util.List;

/** "View as owner": a read-only owner token for the business, its hotels, and how many seconds it lasts. */
public record SupportAccessTokenResponse(String accessToken, long expiresIn, List<String> hotels) {
}
