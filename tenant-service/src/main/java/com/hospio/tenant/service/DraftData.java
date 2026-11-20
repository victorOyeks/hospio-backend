package com.hospio.tenant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.tenant.dto.OnboardingDraftData;
import com.hospio.tenant.dto.OnboardingLogin;
import com.hospio.tenant.dto.OnboardingStepData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads and writes an onboarding draft's {@code data} ({@link OnboardingDraftData}, kept as JSON text on the draft). Data that
 * isn't a draft (saved before this shape existed, or not JSON) reads as an empty draft.
 */
@Component
@RequiredArgsConstructor
public class DraftData {

    private final ObjectMapper objectMapper;

    public String empty() {
        return write(OnboardingDraftData.EMPTY);
    }

    public OnboardingDraftData parse(String json) {
        if (json == null || json.isBlank()) {
            return new OnboardingDraftData(null, null, null, null);
        }
        try {
            OnboardingDraftData data = objectMapper.readValue(json, OnboardingDraftData.class);
            return data == null ? new OnboardingDraftData(null, null, null, null) : data;
        } catch (JsonProcessingException e) {
            return new OnboardingDraftData(null, null, null, null);
        }
    }

    public String write(OnboardingDraftData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Draft data could not be written", e);
        }
    }

    public List<Integer> completedSteps(String json) {
        return completedSteps(parse(json));
    }

    public List<Integer> completedSteps(OnboardingDraftData data) {
        TreeSet<Integer> steps = new TreeSet<>();
        if (data.completedSteps() != null) {
            data.completedSteps().stream().filter(s -> s != null).forEach(steps::add);
        }
        return new ArrayList<>(steps);
    }

    /** Stores a step's form data under its key, marks the step completed and remembers what to do next. */
    public OnboardingDraftData saveStep(OnboardingDraftData data, int step, OnboardingStepData form, String nextAction) {
        return saveStep(data, step, form, nextAction, true);
    }

    /** As above; {@code complete} false keeps the form without marking the step completed (a draft save). */
    public OnboardingDraftData saveStep(OnboardingDraftData data, int step, OnboardingStepData form, String nextAction, boolean complete) {
        Map<String, OnboardingStepData> steps = new LinkedHashMap<>(data.steps() == null ? Map.of() : data.steps());
        steps.put(String.valueOf(step), form == null ? OnboardingStepData.EMPTY : form);
        TreeSet<Integer> completed = new TreeSet<>(completedSteps(data));
        if (complete) {
            completed.add(step);
        }
        return new OnboardingDraftData(steps, new ArrayList<>(completed), nextAction != null ? nextAction : data.nextAction(), data.logins());
    }

    /** Every login recorded in the draft: the top-level list plus any {@code logins} a step saved. */
    public List<OnboardingLogin> logins(OnboardingDraftData data) {
        List<OnboardingLogin> out = new ArrayList<>();
        if (data.logins() != null) {
            out.addAll(data.logins());
        }
        if (data.steps() != null) {
            data.steps().values().stream().filter(s -> s != null && s.logins() != null).forEach(s -> out.addAll(s.logins()));
        }
        return out;
    }

    public OnboardingDraftData addLogin(OnboardingDraftData data, OnboardingLogin login) {
        List<OnboardingLogin> logins = new ArrayList<>(data.logins() == null ? List.of() : data.logins());
        logins.add(login);
        return new OnboardingDraftData(data.steps(), data.completedSteps(), data.nextAction(), logins);
    }
}
