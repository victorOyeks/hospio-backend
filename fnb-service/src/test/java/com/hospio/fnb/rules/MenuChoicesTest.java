package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

class MenuChoicesTest {

    private static final String SPICE = "[{\"group\":\"Spice\",\"choices\":[{\"name\":\"Mild\",\"priceDelta\":0},{\"name\":\"Hot\",\"priceDelta\":500}],\"required\":true,\"multiple\":false},"
            + "{\"group\":\"Extras\",\"choices\":[{\"name\":\"Plantain\",\"priceDelta\":700},{\"name\":\"Egg\",\"priceDelta\":300}],\"required\":false,\"multiple\":true}]";

    @Test
    void chosenChoicesAddTheirPriceDifference() {
        assertThat(MenuChoices.extra("QA Soup", SPICE, "Hot")).isEqualByComparingTo("500");
        assertThat(MenuChoices.extra("QA Soup", SPICE, "hot, Plantain, Egg")).isEqualByComparingTo("1500");
        assertThat(MenuChoices.extra("QA Soup", SPICE, "Mild")).isEqualByComparingTo("0");
    }

    @Test
    void aGroupThatMustBeChosenIsRefusedWhenMissing() {
        assertThatThrownBy(() -> MenuChoices.extra("QA Soup", SPICE, "Plantain")).isInstanceOf(BadRequestException.class).hasMessageContaining("spice for QA Soup");
        assertThatThrownBy(() -> MenuChoices.extra("QA Soup", SPICE, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void itemsWithoutStructuredChoicesCostTheMenuPrice() {
        assertThat(MenuChoices.extra("Jollof", null, "Grilled")).isEqualByComparingTo("0");
        assertThat(MenuChoices.extra("Jollof", "Grilled, Fried", "Grilled")).isEqualByComparingTo("0");
        assertThat(MenuChoices.extra("Jollof", "not json [", "x")).isEqualByComparingTo("0");
    }

    @Test
    void storedChoicesWithExtraFieldsAndOddValuesReadAsBefore() {
        // A price difference stored as text, or missing, adds nothing; unknown fields are ignored; a group without a name is "the choice".
        String stored = "[{\"group\":\"Sides\",\"choices\":[{\"name\":\" Rice \",\"priceDelta\":\"400\",\"id\":\"x\"},{\"name\":\"Beans\"},"
                + "{\"name\":\"Yam\",\"priceDelta\":250.5}, null],\"required\":false,\"multiple\":true,\"colour\":\"red\"},"
                + "{\"choices\":[{\"name\":\"Cold\",\"priceDelta\":0}],\"required\":true}]";
        assertThat(MenuChoices.extra("Stew", stored, "rice, beans, yam, cold")).isEqualByComparingTo("250.5");
        assertThatThrownBy(() -> MenuChoices.extra("Stew", stored, "rice")).isInstanceOf(BadRequestException.class).hasMessage("Choose the choice for Stew");
        assertThat(MenuChoices.chosenWithExtra(stored, "rice, yam")).containsExactly(java.util.Map.entry("Rice", java.math.BigDecimal.ZERO),
                java.util.Map.entry("Yam", new java.math.BigDecimal("250.5")));
    }

    @Test
    void aListOfPlainNamesOrAnEmptyListIsNotStructured() {
        assertThat(MenuChoices.extra("Jollof", "[\"Grilled\",\"Fried\"]", "Grilled")).isEqualByComparingTo("0");
        assertThat(MenuChoices.extra("Jollof", "[]", "Grilled")).isEqualByComparingTo("0");
        assertThat(MenuChoices.chosenWithExtra("[\"Grilled\"]", "Grilled")).isEmpty();
    }
}
