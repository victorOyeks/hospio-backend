package com.hospio.tenant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Save a wizard step: the step number, its form data (kept under that step's key) and what to do next. */
public record OnboardingStepRequest(@Min(1) @Max(10) int step, JsonNode data, String nextAction) {
}
