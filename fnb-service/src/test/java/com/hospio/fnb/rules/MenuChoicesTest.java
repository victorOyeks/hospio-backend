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
}
