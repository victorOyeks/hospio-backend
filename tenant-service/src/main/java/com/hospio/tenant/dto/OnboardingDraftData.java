package com.hospio.tenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * An onboarding draft's {@code data}, as stored (JSON text) and as the wizards read it:
 * <pre>{"steps": {"1": {...}, "2": {...}}, "completedSteps": [1, 2], "nextAction": "...", "logins": [{...}]}</pre>
 * Each step's form is kept under its number so the wizard can be left and resumed; {@code logins} are the accounts created
 * along the way that no step lists (the owner's, made by the handover).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardingDraftData(Map<String, OnboardingStepData> steps, List<Integer> completedSteps, String nextAction,
                                  List<OnboardingLogin> logins) {

    public static final OnboardingDraftData EMPTY = new OnboardingDraftData(Map.of(), null, null, null);
}
