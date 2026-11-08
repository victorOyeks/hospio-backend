package com.hospio.tenant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The shape of an onboarding draft's {@code data}:
 * <pre>{"steps": {"1": {...}, "2": {...}}, "completedSteps": [1, 2], "nextAction": "...", "logins": [{...}]}</pre>
 * Each step's form JSON is kept under its own key so the wizard can be left and resumed; {@code logins}
 * are the accounts created along the way (the handover pack lists them). Data saved before this shape
 * existed is kept under {@code legacy}.
 */
@Component
@RequiredArgsConstructor
public class DraftData {

    private final ObjectMapper objectMapper;

    public String empty() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("steps");
        return write(root);
    }

    public ObjectNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject() && (node.has("steps") || node.has("completedSteps") || node.isEmpty())) {
                return (ObjectNode) node;
            }
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.set("legacy", node);
            return wrapped;
        } catch (JsonProcessingException e) {
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.put("legacy", json);
            return wrapped;
        }
    }

    public String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Draft data could not be written", e);
        }
    }

    public List<Integer> completedSteps(String json) {
        return completedSteps(parse(json));
    }

    public List<Integer> completedSteps(ObjectNode root) {
        TreeSet<Integer> steps = new TreeSet<>();
        root.path("completedSteps").forEach(n -> steps.add(n.asInt()));
        return new ArrayList<>(steps);
    }

    public String nextAction(ObjectNode root) {
        return root.path("nextAction").isTextual() ? root.get("nextAction").asText() : null;
    }

    /** Stores a step's form data under its key, marks the step completed and remembers what to do next. */
    public void saveStep(ObjectNode root, int step, JsonNode data, String nextAction) {
        ObjectNode steps = root.has("steps") && root.get("steps").isObject() ? (ObjectNode) root.get("steps") : root.putObject("steps");
        steps.set(String.valueOf(step), data == null ? objectMapper.createObjectNode() : data);
        TreeSet<Integer> completed = new TreeSet<>(completedSteps(root));
        completed.add(step);
        ArrayNode arr = root.putArray("completedSteps");
        completed.forEach(arr::add);
        if (nextAction != null) {
            root.put("nextAction", nextAction);
        }
    }

    /** Every login recorded in the draft: the top-level list plus any {@code logins} array a step saved. */
    public List<JsonNode> logins(ObjectNode root) {
        List<JsonNode> out = new ArrayList<>();
        root.path("logins").forEach(out::add);
        root.path("steps").forEach(step -> step.path("logins").forEach(out::add));
        return out;
    }

    public void addLogin(ObjectNode root, ObjectNode login) {
        ArrayNode arr = root.has("logins") && root.get("logins").isArray() ? (ArrayNode) root.get("logins") : root.putArray("logins");
        arr.add(login);
    }

    public ObjectNode object() {
        return objectMapper.createObjectNode();
    }
}
