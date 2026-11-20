package com.hospio.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Save a wizard step: the step number, its form data (kept under that step's key) and what to do next. {@code draft: true}
 * keeps the form without completing the step ("Save and finish later", the board's next action): the step's required
 * fields are not asked for, only a value that is typed is checked.
 */
public record OnboardingStepRequest(@Min(1) @Max(10) int step, OnboardingStepData data, String nextAction, Boolean draft) {

    public OnboardingStepRequest(int step, OnboardingStepData data, String nextAction) {
        this(step, data, nextAction, null);
    }

    public boolean isDraft() {
        return Boolean.TRUE.equals(draft);
    }
}
