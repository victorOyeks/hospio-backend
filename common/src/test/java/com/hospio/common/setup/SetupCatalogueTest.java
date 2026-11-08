package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A new hotel answers every section fully; stored values win over defaults; unknown keys and sections survive the merge. */
class SetupCatalogueTest {

    @Test
    void mergeLaysStoredValuesOverEveryCatalogueSection() {
        Map<String, Map<String, JsonNode>> stored = Map.of(
                "BASICS", Map.of("checkInTime", JsonNodeFactory.instance.textNode("15:00"), "customKey", JsonNodeFactory.instance.textNode("kept")),
                "LEGACY_SECTION", Map.of("x", JsonNodeFactory.instance.numberNode(1)));

        Map<String, Map<String, JsonNode>> merged = SetupCatalogue.merge(stored);

        assertThat(merged.keySet()).containsAll(SetupCatalogue.SECTIONS.keySet()).contains("LEGACY_SECTION");
        assertThat(merged.get("BASICS").get("checkInTime").asText()).isEqualTo("15:00");
        assertThat(merged.get("BASICS").get("checkOutTime").asText()).isEqualTo("12:00");
        assertThat(merged.get("BASICS").get("customKey").asText()).isEqualTo("kept");
        assertThat(merged.get("PAYMENTS").get("receipts").asText()).isEqualTo("WHATSAPP");
        assertThat(merged.get("TAXES_CURRENCY").get("vatPct").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(merged.get("LEGACY_SECTION").get("x").asInt()).isEqualTo(1);
    }

    @Test
    void emptyStoreStillAnswersEverySection() {
        Map<String, Map<String, JsonNode>> merged = SetupCatalogue.merge(null);
        assertThat(merged).hasSize(SetupCatalogue.SECTIONS.size());
        assertThat(merged.get("APPROVAL_LIMITS").get("managerExpenseLimit").asLong()).isEqualTo(200000);
        assertThat(SetupCatalogue.isOwnerOnly("APPROVAL_LIMITS")).isTrue();
        assertThat(SetupCatalogue.isOwnerOnly("BRAND")).isFalse();
    }

    @Test
    void templateCarriesSectionsAndNumberedLists() {
        var template = SetupCatalogue.templateJson();
        assertThat(template.path("sections").path("BASICS").path("timezone").asText()).isEqualTo("Africa/Lagos");
        var shifts = template.path("lists").path("SHIFTS");
        assertThat(shifts).hasSize(3);
        assertThat(shifts.get(0).path("code").asText()).isEqualTo("MORNING");
        assertThat(shifts.get(2).path("sortOrder").asInt()).isEqualTo(3);
        assertThat(template.path("lists").path("DEPARTMENTS")).hasSize(8);
    }
}
