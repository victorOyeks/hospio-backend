package com.hospio.common.integrations.settings;

import java.util.List;

/**
 * One field of a provider's settings: {@code key} is the settings record's component name, {@code options} (null for free text) the
 * only values it takes, {@code env} the server variable it falls back to while nothing is saved (shown in the hint).
 */
public record IntegrationField(String key, String label, FieldType type, String hint, List<String> options, String env) {

    public boolean secret() {
        return type == FieldType.SECRET;
    }

    static IntegrationField entry(String key, String label, FieldType type, String env, String hint) {
        return new IntegrationField(key, label, type, hint, null, env);
    }

    static IntegrationField choice(String key, String label, String env, String hint, String... options) {
        return new IntegrationField(key, label, FieldType.TEXT, hint, List.of(options), env);
    }
}
