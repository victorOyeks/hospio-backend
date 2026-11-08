package com.hospio.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Save a wizard step: the step number and the form data for it as JSON. */
public record OnboardingStepRequest(@Min(1) @Max(10) int step, String data) {
}
