package com.hospio.fnb.rules;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

/**
 * A menu item request's {@code recipe} as the web app sends it: a JSON array of {@code {stockItemId, quantity}}, or a string holding
 * one. {@code null} sent explicitly clears the recipe (no lines); leaving the field out leaves the request's {@code recipe} null, which
 * keeps the stored recipe. {@code wellFormed} is false when the value isn't a list of recipe lines; {@link Recipes#parse} refuses it
 * with the usual sentence.
 */
@JsonDeserialize(using = RecipeInput.Reader.class)
public record RecipeInput(List<Recipes.Line> lines, boolean wellFormed) {

    public static final RecipeInput CLEARED = new RecipeInput(List.of(), true);
    public static final RecipeInput MALFORMED = new RecipeInput(List.of(), false);

    /** The recipe from its JSON text (an array, or {@code null}); malformed when it isn't a list of recipe lines. */
    public static RecipeInput ofJson(String json) {
        return Recipes.input(json);
    }

    /** Reads the value with the streaming parser: a string is taken as the JSON it holds, anything else as JSON itself. */
    static final class Reader extends StdDeserializer<RecipeInput> {

        Reader() {
            super(RecipeInput.class);
        }

        @Override
        public RecipeInput deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return ofJson(p.getText());
            }
            StringWriter json = new StringWriter();
            try (JsonGenerator g = Recipes.jsonFactory().createGenerator(json)) {
                g.copyCurrentStructure(p);
            }
            return ofJson(json.toString());
        }

        /** An explicit {@code "recipe": null} clears the recipe. */
        @Override
        public RecipeInput getNullValue(DeserializationContext ctxt) {
            return CLEARED;
        }

        /** A request without {@code recipe} keeps the stored one. */
        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }
}
