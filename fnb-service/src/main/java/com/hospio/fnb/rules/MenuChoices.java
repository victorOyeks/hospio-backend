package com.hospio.fnb.rules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.hospio.common.exception.BadRequestException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A menu item's choices ({@code [{group, choices:[{name, priceDelta}], required, multiple}]}) against what the guest chose, sent as the
 * line's {@code options} text ("Grilled, Hot"). The chosen choices' price differences are added to the portion price, and a group that
 * must be chosen has to be. Items with no structured choices (none, or the older comma list) cost the menu price whatever is chosen.
 */
public final class MenuChoices {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChoiceGroup>> GROUPS = new TypeReference<>() {
    };

    private MenuChoices() {
    }

    /** One group of a menu item's choices, as the web app stores it in the item's {@code options} text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChoiceGroup(String group, List<Choice> choices, Boolean required, Boolean multiple) {

        List<Choice> choiceList() {
            return choices == null ? List.of() : choices;
        }

        boolean mustBeChosen() {
            return Boolean.TRUE.equals(required);
        }

        /** The group's name for a refusal; "choice" when it has none. */
        String label() {
            return group == null ? "choice" : group;
        }
    }

    /** One choice; {@code priceDelta} is what it adds to a portion, null unless it was stored as a number. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(String name, @JsonDeserialize(using = NumberOnly.class) BigDecimal priceDelta) {

        /** The name trimmed, "" when there is none. */
        String trimmedName() {
            return name == null ? "" : name.trim();
        }
    }

    /** What the choices add to one portion. */
    public static BigDecimal extra(String itemName, String optionsJson, String chosen) {
        List<ChoiceGroup> groups = groups(optionsJson);
        if (groups == null) {
            return BigDecimal.ZERO;
        }
        Set<String> picked = chosen == null ? Set.of()
                : Arrays.stream(chosen.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        BigDecimal extra = BigDecimal.ZERO;
        Set<String> counted = new HashSet<>();
        for (ChoiceGroup g : groups) {
            if (g == null) {
                continue;
            }
            boolean any = false;
            for (Choice c : g.choiceList()) {
                String name = c == null ? "" : c.trimmedName().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && picked.contains(name)) {
                    any = true;
                    if (counted.add(name) && c.priceDelta() != null) {
                        extra = extra.add(c.priceDelta());
                    }
                }
            }
            if (!any && g.mustBeChosen()) {
                throw new BadRequestException("Choose the " + g.label().toLowerCase(Locale.ROOT) + " for " + itemName);
            }
        }
        return extra;
    }

    /** The chosen choices that are on the item's structured list, with what each adds per portion (no required-group check). */
    public static List<Map.Entry<String, BigDecimal>> chosenWithExtra(String optionsJson, String chosen) {
        List<ChoiceGroup> groups = groups(optionsJson);
        if (groups == null || chosen == null || chosen.isBlank()) {
            return List.of();
        }
        Set<String> picked = Arrays.stream(chosen.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        List<Map.Entry<String, BigDecimal>> out = new ArrayList<>();
        Set<String> counted = new HashSet<>();
        for (ChoiceGroup g : groups) {
            if (g == null) {
                continue;
            }
            for (Choice c : g.choiceList()) {
                String name = c == null ? "" : c.trimmedName();
                if (!name.isEmpty() && picked.contains(name.toLowerCase(Locale.ROOT)) && counted.add(name.toLowerCase(Locale.ROOT))) {
                    out.add(Map.entry(name, c.priceDelta() != null ? c.priceDelta() : BigDecimal.ZERO));
                }
            }
        }
        return out;
    }

    /** The structured choice groups, or null for no choices, the older comma list, or anything that isn't a list of groups. */
    static List<ChoiceGroup> groups(String json) {
        if (json == null || json.isBlank() || !json.trim().startsWith("[")) {
            return null;
        }
        try {
            List<ChoiceGroup> groups = MAPPER.readValue(json, GROUPS);
            if (groups == null || groups.isEmpty() || groups.get(0) == null) {
                return null;
            }
            return groups;
        } catch (Exception e) {
            return null;
        }
    }

    /** A price difference counts only when it was stored as a JSON number; text, true/false or an object reads as none. */
    static final class NumberOnly extends StdDeserializer<BigDecimal> {

        NumberOnly() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != null && p.currentToken().isNumeric()) {
                return p.getDecimalValue();
            }
            p.skipChildren();
            return null;
        }
    }
}
