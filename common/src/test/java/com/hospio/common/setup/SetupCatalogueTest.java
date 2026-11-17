package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A new hotel answers every section fully; stored values win over defaults; the wire shape stays {SECTION: {key: value}}. */
class SetupCatalogueTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void storedValuesAreLaidOverEveryCatalogueSection() {
        HotelSetup stored = HotelSetup.of(name -> SetupCatalogue.BASICS.equals(name) ? new SetupSection.Basics("15:00", null, null, null) : null);

        HotelSetup merged = SetupCatalogue.withDefaults(stored);

        assertThat(merged.basics().checkInTime()).isEqualTo("15:00");
        assertThat(merged.basics().checkOutTime()).isEqualTo("12:00");
        assertThat(merged.payments().receipts()).isEqualTo("WHATSAPP");
        assertThat(merged.taxesCurrency().vatPct()).isEqualByComparingTo("7.5");
        for (String section : SetupCatalogue.SECTIONS.keySet()) {
            assertThat(SetupCatalogue.present(merged.get(section))).hasSameSizeAs(SetupCatalogue.keys(SetupCatalogue.SECTIONS.get(section)));
        }
    }

    @Test
    void emptyStoreStillAnswersEverySection() {
        HotelSetup merged = SetupCatalogue.withDefaults((HotelSetup) null);
        assertThat(merged).isEqualTo(HotelSetup.DEFAULTS);
        assertThat(merged.approvalLimits().managerExpenseLimit()).isEqualByComparingTo("200000");
        assertThat(SetupCatalogue.isOwnerOnly("APPROVAL_LIMITS")).isTrue();
        assertThat(SetupCatalogue.isOwnerOnly("BRAND")).isFalse();
        assertThat(SetupCatalogue.keyCount()).isEqualTo(50);
    }

    @Test
    void theDefaultsGoOnTheWireWithTheSameKeysAndValuesAsBefore() throws Exception {
        String json = mapper.writeValueAsString(HotelSetup.DEFAULTS);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> wire = mapper.readValue(json, Map.class);
        assertThat(wire.keySet()).containsExactlyInAnyOrderElementsOf(SetupCatalogue.SECTIONS.keySet());
        assertThat(wire.get("PAYMENTS")).containsEntry("depositPct", 0).containsEntry("receipts", "WHATSAPP").containsEntry("allowCash", true)
                .containsOnlyKeys("allowCash", "allowCard", "allowTransfer", "receipts", "depositPct", "tillFloat", "bankName", "bankAccount",
                        "guestPaysPlatformFee", "platformFeePct")
                .containsEntry("guestPaysPlatformFee", false);
        assertThat(wire.get("TAXES_CURRENCY")).containsEntry("vatPct", 7.5);
        assertThat(wire.get("APPROVAL_LIMITS")).containsEntry("managerExpenseLimit", 200000).containsEntry("discountPct", 10);
        assertThat(wire.get("BRAND")).containsEntry("primaryColour", "#4c091e").containsEntry("darkSidebar", true);
        assertThat(mapper.readValue(json, HotelSetup.class)).isEqualTo(HotelSetup.DEFAULTS);
    }

    @Test
    void unknownKeysAreIgnoredAndAbsentKeysStayNull() throws Exception {
        SetupSection.Payments p = mapper.readValue("{\"depositPct\":20,\"somethingElse\":\"x\"}", SetupSection.Payments.class);
        assertThat(p.depositPct()).isEqualByComparingTo("20");
        assertThat(p.tillFloat()).isNull();
        assertThat(SetupCatalogue.overlay(SetupSection.Payments.DEFAULTS, p).tillFloat()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(SetupCatalogue.isEmpty(mapper.readValue("{}", SetupSection.Payments.class))).isTrue();
        assertThat(SetupCatalogue.sameValue(new BigDecimal("7.50"), new BigDecimal("7.5"))).isTrue();
    }

    @Test
    void templateCarriesSectionsAndNumberedLists() throws Exception {
        SetupTemplateSettings template = SetupCatalogue.template();
        assertThat(template.sections().basics().timezone()).isEqualTo("Africa/Lagos");
        assertThat(template.lists().get("SHIFTS")).hasSize(3);
        assertThat(template.lists().get("SHIFTS").get(0).code()).isEqualTo("MORNING");
        assertThat(template.lists().get("SHIFTS").get(2).sortOrder()).isEqualTo(3);
        assertThat(template.lists().get("DEPARTMENTS")).hasSize(8);

        String json = mapper.writeValueAsString(template);
        @SuppressWarnings("unchecked")
        Map<String, Object> wire = mapper.readValue(json, Map.class);
        assertThat(wire).containsOnlyKeys("sections", "lists");
        assertThat(mapper.readValue(json, SetupTemplateSettings.class)).isEqualTo(template);
    }

    @Test
    void aTemplateMayBeFlatAndListEntriesMayBeLabels() throws Exception {
        SetupTemplateSettings flat = mapper.readValue("{\"BASICS\":{\"checkInTime\":\"13:00\"},\"STAFF\":{\"lateMinutes\":5}}", SetupTemplateSettings.class);
        assertThat(flat.sectionsToApply().basics().checkInTime()).isEqualTo("13:00");
        assertThat(flat.sectionsToApply().staff().lateMinutes()).isEqualTo(5);
        assertThat(flat.listsToApply()).isEmpty();

        SetupTemplateSettings nested = mapper.readValue(
                "{\"sections\":{\"POS\":{\"provider\":\"opay\"}},\"lists\":{\"PUBLIC_AREAS\":[\"Lobby\",{\"code\":\"POOL\",\"label\":\"Pool\",\"sortOrder\":7}]}}",
                SetupTemplateSettings.class);
        assertThat(nested.sectionsToApply().pos().provider()).isEqualTo("opay");
        assertThat(nested.sectionsToApply().basics()).isNull();
        assertThat(nested.listsToApply().get("PUBLIC_AREAS")).containsExactly(new SetupListEntry(null, "Lobby", null),
                new SetupListEntry("POOL", "Pool", 7));

        // Only lists, as before: the top-level sections of a template that has "lists" are not read.
        SetupTemplateSettings listsOnly = mapper.readValue("{\"BASICS\":{\"checkInTime\":\"13:00\"},\"lists\":{}}", SetupTemplateSettings.class);
        assertThat(listsOnly.sectionsToApply()).isNull();
    }
}
