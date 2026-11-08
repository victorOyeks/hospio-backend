package com.hospio.tenant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.OnboardingDraft;
import java.time.Instant;
import java.util.List;

/** A business's wizard: 10 steps for the back office, 4 for a self-serve owner. {@code data} is the saved form JSON by step. */
public record OnboardingResponse(String id, String businessId, String businessName, Business.Source source, int step, int totalSteps,
                                 List<Integer> completedSteps, String assignedTo, String assignedToName, OnboardingDraft.Status status,
                                 String nextAction, Instant startedAt, Instant updatedAt, JsonNode data) {
}
