package com.hospio.fnb.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;

/** A menu item's recipe: what one portion draws from the store, stored as JSON text. */
public final class Recipes {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };

    private Recipes() {
    }

    public record Line(String stockItemId, BigDecimal quantity) {
    }

    /** From a request value: a JSON array, or a string holding one. Null or empty means no recipe. */
    public static List<Line> parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        try {
            JsonNode array = node.isTextual() ? (node.asText().isBlank() ? null : MAPPER.readTree(node.asText())) : node;
            if (array == null || array.isNull()) {
                return List.of();
            }
            if (!array.isArray()) {
                throw new BadRequestException("The recipe is a list of {stockItemId, quantity}");
            }
            List<Line> lines = MAPPER.convertValue(array, LINES);
            for (Line l : lines) {
                if (l.stockItemId() == null || l.stockItemId().isBlank() || l.quantity() == null || l.quantity().signum() <= 0) {
                    throw new BadRequestException("Every recipe line needs a stock item and a quantity above zero");
                }
            }
            return lines;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new BadRequestException("The recipe is a list of {stockItemId, quantity}");
        }
    }

    public static String write(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("The recipe could not be stored");
        }
    }

    public static List<Line> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LINES);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
