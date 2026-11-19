package com.hospio.common.venue;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VenueWordingTest {

    @AfterEach
    void reset() {
        VenuePlaces.useForTests(null);
    }

    @Test
    void aHotelAndAnythingUnknownReadAsBefore() {
        for (VenueWording w : List.of(VenueWording.of((VenueType) null), VenueWording.of(VenueType.HOTEL), VenueWording.of("nonsense"),
                VenueWording.of(""))) {
            assertThat(w.place()).isEqualTo("hotel");
            assertThat(w.places()).isEqualTo("hotels");
            assertThat(w.capital()).isEqualTo("Hotel");
            assertThat(w.possessive()).isEqualTo("hotel's");
            assertThat(w.a()).isEqualTo("a hotel");
            assertThat(w.setup()).isEqualTo("Hotel setup");
            assertThat(w.isHotel()).isTrue();
        }
    }

    @Test
    void aVenueIsNamedByItsType() {
        assertThat(VenueWording.of(VenueType.LOUNGE).place()).isEqualTo("lounge");
        assertThat(VenueWording.of("bar").places()).isEqualTo("bars");
        assertThat(VenueWording.of(VenueType.RESTAURANT).setup()).isEqualTo("Restaurant setup");
        assertThat(VenueWording.of(VenueType.CLUB).capitalPlaces()).isEqualTo("Clubs");
        assertThat(VenueWording.of(VenueType.LOUNGE).isHotel()).isFalse();
    }

    @Test
    void aBusinessReadsHotelsOnlyWhileEveryLocationIsAHotel() {
        assertThat(VenueWording.ofBusiness(null, null).places()).isEqualTo("hotels");
        assertThat(VenueWording.ofBusiness("HOTEL", List.of()).places()).isEqualTo("hotels");
        assertThat(VenueWording.ofBusiness("HOTEL", List.of("HOTEL", "HOTEL")).place()).isEqualTo("hotel");
        assertThat(VenueWording.ofBusiness("LOUNGE", null).places()).isEqualTo("locations");
        assertThat(VenueWording.ofBusiness("MIXED", List.of()).place()).isEqualTo("location");
        assertThat(VenueWording.ofBusiness("HOTEL", List.of("HOTEL", "LOUNGE")).places()).isEqualTo("locations");
        assertThat(VenueWording.ofBusiness("MIXED", List.of("HOTEL")).places()).isEqualTo("hotels");
        assertThat(VenueWording.LOCATION.setup()).isEqualTo("Setup");
    }

    @Test
    void withoutTenantServiceEveryPlaceIsAHotel() {
        assertThat(VenuePlaces.at("h1")).isEqualTo(VenueWording.HOTEL);
        assertThat(VenuePlaces.here()).isEqualTo(VenueWording.HOTEL);
        assertThat(VenuePlaces.business()).isEqualTo(VenueWording.HOTEL);
        VenuePlaces unconfigured = new VenuePlaces();
        VenuePlaces.useForTests(unconfigured);
        assertThat(VenuePlaces.at("h1").setup()).isEqualTo("Hotel setup");
    }

    @Test
    void theLocationInContextIsNamed() {
        VenuePlaces places = new VenuePlaces();
        places.remember("h|lounge-1", VenueWording.of(VenueType.LOUNGE));
        places.remember("b|biz-1", VenueWording.LOCATION);
        VenuePlaces.useForTests(places);
        String text = TenantContext.runWith(Tenant.systemFor("biz-1", "lounge-1", null, null),
                () -> "This " + VenuePlaces.here().place() + " doesn't take cash; " + VenuePlaces.here().setup() + "; your "
                        + VenuePlaces.business().places());
        assertThat(text).isEqualTo("This lounge doesn't take cash; Lounge setup; your locations");
        assertThat(VenuePlaces.at("other").place()).isEqualTo("hotel");
    }
}
