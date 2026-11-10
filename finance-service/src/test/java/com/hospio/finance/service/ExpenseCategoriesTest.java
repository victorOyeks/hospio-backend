package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Category codes: every spelling an expense may carry (typed label, staff code, new code) normalises to one key; labels read as words. */
class ExpenseCategoriesTest {

    @Test
    void legacySpellingsShareTheCode() {
        assertThat(ExpenseCategories.codeOf("Fuel & power")).isEqualTo("FUEL_AND_POWER");
        assertThat(ExpenseCategories.codeOf("FUEL_AND_POWER")).isEqualTo("FUEL_AND_POWER");
        assertThat(ExpenseCategories.codeOf("  fuel and   power ")).isEqualTo("FUEL_AND_POWER");
        assertThat(ExpenseCategories.codeOf("REPAIRS")).isEqualTo(ExpenseCategories.codeOf("Repairs"));
        assertThat(ExpenseCategories.codeOf("SUPPLIES")).isEqualTo("SUPPLIES");
        assertThat(ExpenseCategories.codeOf("Staff welfare")).isEqualTo("STAFF_WELFARE");
        assertThat(ExpenseCategories.codeOf("--Other--")).isEqualTo("OTHER");
        assertThat(ExpenseCategories.codeOf(null)).isEmpty();
        assertThat(ExpenseCategories.codeOf(" & ")).isEqualTo("AND");
    }

    @Test
    void theStandardListHasTwelveDistinctCodesEndingWithOther() {
        assertThat(ExpenseCategories.DEFAULTS).hasSize(12);
        assertThat(ExpenseCategories.DEFAULTS.stream().map(ExpenseCategories::codeOf).distinct()).hasSize(12);
        assertThat(ExpenseCategories.codeOf(ExpenseCategories.DEFAULTS.get(11))).isEqualTo(ExpenseCategories.OTHER);
    }

    @Test
    void labelsReadAsWords() {
        Map<String, String> labels = Map.of("FUEL_AND_POWER", "Fuel & power", "REPAIRS", "Repairs");
        assertThat(ExpenseCategories.labelOf(labels, "FUEL_AND_POWER")).isEqualTo("Fuel & power");
        assertThat(ExpenseCategories.labelOf(labels, "Fuel & power")).isEqualTo("Fuel & power");
        assertThat(ExpenseCategories.labelOf(labels, "REPAIRS")).isEqualTo("Repairs");
        assertThat(ExpenseCategories.labelOf(labels, "SUPPLIES")).isEqualTo("Supplies");
        assertThat(ExpenseCategories.labelOf(labels, "FOOD_AND_DRINK")).isEqualTo("Food and drink");
        assertThat(ExpenseCategories.labelOf(labels, "Generator diesel")).isEqualTo("Generator diesel");
    }

    @Test
    void aRenamedCategoryIsFoundByItsNewLabel() {
        com.hospio.finance.repository.ExpenseCategoryRepository repo = org.mockito.Mockito.mock(com.hospio.finance.repository.ExpenseCategoryRepository.class);
        com.hospio.finance.entity.ExpenseCategory gen = com.hospio.finance.entity.ExpenseCategory.builder().code("GENERATOR").label("Generator diesel")
                .sortOrder(1).build();
        org.mockito.Mockito.when(repo.findByHotelIdOrderBySortOrderAscLabelAsc("h1")).thenReturn(java.util.List.of(gen));
        ExpenseCategories cats = new ExpenseCategories(repo);
        assertThat(cats.normalise("h1", "Generator diesel")).isEqualTo("GENERATOR");
        assertThat(cats.normalise("h1", "generator DIESEL")).isEqualTo("GENERATOR");
        assertThat(cats.normalise("h1", "GENERATOR")).isEqualTo("GENERATOR");
        assertThat(cats.normalise("h1", "REPAIRS")).isEqualTo("REPAIRS");
    }

    @Test
    void aNameIsNeeded() {
        assertThatThrownBy(() -> ExpenseCategories.cleanLabel("   ")).isInstanceOf(BadRequestException.class).hasMessage("Give the category a name");
        assertThatThrownBy(() -> ExpenseCategories.cleanLabel("!!")).isInstanceOf(BadRequestException.class).hasMessage("Give the category a name");
        assertThat(ExpenseCategories.cleanLabel("  Pool   chemicals ")).isEqualTo("Pool chemicals");
    }
}
