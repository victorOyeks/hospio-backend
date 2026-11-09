package com.hospio.fnb.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A menu item's choices ({@code [{group, choices:[{name, priceDelta}], required, multiple}]}) against what the guest chose, sent as the
 * line's {@code options} text ("Grilled, Hot"). The chosen choices' price differences are added to the portion price, and a group that
 * must be chosen has to be. Items with no structured choices (none, or the older comma list) cost the menu price whatever is chosen.
 */
public final class MenuChoices {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MenuChoices() {
    }

    /** What the choices add to one portion. */
    public static BigDecimal extra(String itemName, String optionsJson, String chosen) {
        JsonNode groups = groups(optionsJson);
        if (groups == null) {
            return BigDecimal.ZERO;
        }
        Set<String> picked = chosen == null ? Set.of()
                : Arrays.stream(chosen.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        BigDecimal extra = BigDecimal.ZERO;
        Set<String> counted = new HashSet<>();
        for (JsonNode g : groups) {
            boolean any = false;
            for (JsonNode c : g.path("choices")) {
                String name = c.path("name").asText("").trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && picked.contains(name)) {
                    any = true;
                    if (counted.add(name) && c.path("priceDelta").isNumber()) {
                        extra = extra.add(c.path("priceDelta").decimalValue());
                    }
                }
            }
            if (!any && g.path("required").asBoolean(false)) {
                throw new BadRequestException("Choose the " + g.path("group").asText("choice").toLowerCase(Locale.ROOT) + " for " + itemName);
            }
        }
        return extra;
    }

    private static JsonNode groups(String json) {
        if (json == null || json.isBlank() || !json.trim().startsWith("[")) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray() || n.isEmpty() || !n.get(0).isObject()) {
                return null;
            }
            return n;
        } catch (Exception e) {
            return null;
        }
    }
}
