package com.hospio.tenant.dto;

import com.hospio.tenant.entity.OnboardingDraft;
import java.time.Instant;

public record OnboardingResponse(String id, String businessId, String businessName, int step, String data, String assignedTo,
                                 OnboardingDraft.Status status, Instant updatedAt) {
}
