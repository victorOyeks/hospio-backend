package com.hospio.common.setup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of a Hotel setup list in a template ({@code {"code": "MORNING", "label": "Morning (07:00-15:00)", "sortOrder": 1}}).
 * A template may also give an entry as its label alone ({@code "Lobby"}); {@code code} and {@code sortOrder} are then null and
 * the entry takes its place in the list as its order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetupListEntry(String code, String label, Integer sortOrder) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public SetupListEntry(@JsonProperty("code") String code, @JsonProperty("label") String label, @JsonProperty("sortOrder") Integer sortOrder) {
        this.code = code;
        this.label = label;
        this.sortOrder = sortOrder;
    }

    /** An entry given as its label alone. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SetupListEntry ofLabel(String label) {
        return new SetupListEntry(null, label, null);
    }
}
