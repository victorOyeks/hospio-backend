package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.fnb.dto.MenuItemRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The recipe as the web app sends it (an array, or a string holding one) and as it is stored, read without JSON trees. */
class RecipesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEAD = "{\"category\":\"Mains\",\"name\":\"Jollof\",\"price\":2500";

    private static MenuItemRequest request(String recipeField) throws Exception {
        return MAPPER.readValue(HEAD + recipeField + "}", MenuItemRequest.class);
    }

    @Test
    void theWebAppsArrayIsRead() throws Exception {
        MenuItemRequest r = request(",\"recipe\":[{\"stockItemId\":\"rice\",\"quantity\":0.25},{\"stockItemId\":\"oil\",\"quantity\":\"2\"}]");
        assertThat(Recipes.parse(r.recipe())).containsExactly(new Recipes.Line("rice", new BigDecimal("0.25")), new Recipes.Line("oil", new BigDecimal("2")));
    }

    @Test
    void aStringHoldingTheArrayIsReadToo() throws Exception {
        MenuItemRequest r = request(",\"recipe\":\"[{\\\"stockItemId\\\":\\\"rice\\\",\\\"quantity\\\":1}]\"");
        assertThat(Recipes.parse(r.recipe())).containsExactly(new Recipes.Line("rice", new BigDecimal("1")));
    }

    @Test
    void leftOutKeepsTheRecipeAndNullOrEmptyClearsIt() throws Exception {
        assertThat(request("").recipe()).isNull();
        for (String cleared : List.of(",\"recipe\":null", ",\"recipe\":[]", ",\"recipe\":\"\"", ",\"recipe\":\"  \"", ",\"recipe\":\"null\"")) {
            MenuItemRequest r = request(cleared);
            assertThat(r.recipe()).as(cleared).isNotNull();
            assertThat(Recipes.parse(r.recipe())).as(cleared).isEmpty();
            assertThat(Recipes.write(Recipes.parse(r.recipe()))).as(cleared).isNull();
        }
    }

    @Test
    void anythingButAListOfLinesIsRefusedWithTheSameSentence() throws Exception {
        for (String bad : List.of(",\"recipe\":{\"stockItemId\":\"rice\"}", ",\"recipe\":5", ",\"recipe\":true", ",\"recipe\":\"not json\"",
                ",\"recipe\":\"{}\"", ",\"recipe\":[\"rice\"]", ",\"recipe\":[{\"stockItemId\":\"rice\",\"quantity\":1,\"extra\":1}]",
                ",\"recipe\":[{\"stockItemId\":\"rice\",\"quantity\":\"lots\"}]")) {
            MenuItemRequest r = request(bad);
            assertThatThrownBy(() -> Recipes.parse(r.recipe())).as(bad).isInstanceOf(BadRequestException.class)
                    .hasMessage("The recipe is a list of {stockItemId, quantity}");
        }
    }

    @Test
    void everyLineNeedsAStockItemAndAQuantityAboveZero() throws Exception {
        for (String bad : List.of(",\"recipe\":[{\"stockItemId\":\"\",\"quantity\":1}]", ",\"recipe\":[{\"stockItemId\":\"rice\",\"quantity\":0}]",
                ",\"recipe\":[{\"stockItemId\":\"rice\"}]", ",\"recipe\":[null]")) {
            MenuItemRequest r = request(bad);
            assertThatThrownBy(() -> Recipes.parse(r.recipe())).as(bad).isInstanceOf(BadRequestException.class)
                    .hasMessage("Every recipe line needs a stock item and a quantity above zero");
        }
    }

    @Test
    void storedRecipesReadBackAsTheyWereWritten() {
        String stored = "[{\"stockItemId\":\"st-rice\",\"quantity\":0.25},{\"stockItemId\":\"st-oil\",\"quantity\":2}]";
        assertThat(Recipes.read(stored)).containsExactly(new Recipes.Line("st-rice", new BigDecimal("0.25")), new Recipes.Line("st-oil", new BigDecimal("2")));
        assertThat(Recipes.write(Recipes.read(stored))).isEqualTo(stored);
        assertThat(Recipes.read(null)).isEmpty();
        assertThat(Recipes.read("not json")).isEmpty();
    }
}
