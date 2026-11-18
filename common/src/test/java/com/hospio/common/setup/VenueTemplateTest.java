package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The "Lounge / bar / restaurant" template (docs/plan-venue-type.md, phase 3): venue rules, nothing stored for stays. */
class VenueTemplateTest {

    @Test
    void theVenueTemplateLeavesEveryStayKeyAloneSoHotelDefaultsApplyOnceThereAreRooms() {
        HotelSetup v = SetupCatalogue.VENUE_DEFAULTS;
        assertThat(v.stayRules()).isNull();
        assertThat(v.housekeeping()).isNull();
        assertThat(v.basics().checkInTime()).isNull();
        assertThat(v.basics().checkOutTime()).isNull();
        assertThat(v.outlets().roomCharge()).isNull();
        assertThat(v.nightAudit().arrivalsResolvedFirst()).isNull();

        // Laid under the catalogue (what a read answers), the stay keys carry the hotel defaults and the rest the venue's.
        HotelSetup read = SetupCatalogue.withDefaults(v);
        assertThat(read.stayRules()).isEqualTo(SetupSection.StayRules.DEFAULTS);
        assertThat(read.basics().checkInTime()).isEqualTo("14:00");
        assertThat(read.outlets().roomCharge()).isTrue();
        assertThat(read.outlets().tabLimit()).isEqualByComparingTo("100000");
    }

    @Test
    void theVenueDefaultsAreTabsReceiptsOnlyAndNoWaiterPayments() {
        HotelSetup v = SetupCatalogue.VENUE_DEFAULTS;
        assertThat(v.outlets().tabs()).isTrue();
        assertThat(v.outlets().tabMaxHours()).isEqualTo(4);
        assertThat(v.outlets().tabNameRequired()).isTrue();
        assertThat(v.outlets().waitersTakePayment()).isFalse();
        assertThat(v.outlets().waiterCash()).isFalse();
        assertThat(v.taxesCurrency().serviceChargePct()).isEqualByComparingTo("0");
        assertThat(v.taxesCurrency().vatPct()).isEqualByComparingTo("7.5");
        assertThat(v.messaging().sendConfirmation()).isFalse();
        assertThat(v.messaging().arrivalMessage()).isFalse();
        assertThat(v.messaging().sendReceipts()).isTrue();
        assertThat(v.payments().tillFloat()).isEqualByComparingTo("10000");
        assertThat(v.basics().openingTime()).isEqualTo("10:00");
        assertThat(v.basics().receiptFooter()).isNotBlank();
    }

    @Test
    void theTemplateTravelsAsJsonAndCarriesVenueReasonsNotBookingOnes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SetupTemplateSettings t = mapper.readValue(mapper.writeValueAsString(SetupCatalogue.venueTemplate()), SetupTemplateSettings.class);
        assertThat(t.sectionsToApply().stayRules()).isNull();
        assertThat(t.sectionsToApply().outlets().tabNameRequired()).isTrue();
        assertThat(t.listsToApply().get("VOID_REASONS")).extracting(SetupListEntry::code).contains("RANG_UP_TWICE", "SENT_BACK");
        assertThat(t.listsToApply().get("CANCEL_REASONS")).extracting(SetupListEntry::code).doesNotContain("NO_SHOW", "BOOKED_ELSEWHERE");
        assertThat(t.listsToApply()).doesNotContainKeys("HOUSEKEEPING_CHECKLIST", "PUBLIC_AREAS");
        assertThat(SetupCatalogue.defaultsFor(true)).isEqualTo(HotelSetup.DEFAULTS);
        assertThat(SetupCatalogue.listsFor(false)).isSameAs(SetupCatalogue.VENUE_LISTS);
    }
}
