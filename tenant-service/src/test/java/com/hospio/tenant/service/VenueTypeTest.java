package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.venue.VenueType;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.dto.CreateHotelRequest;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Hotel;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Venue types (docs/plan-venue-type.md): a location's type, the accommodation capability, and what the handover pack says. */
class VenueTypeTest {

    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final HotelRepository hotels = mock(HotelRepository.class);
    private final BusinessService service = new BusinessService(businesses, hotels, mock(OnboardingDraftRepository.class),
            new DraftData(new ObjectMapper()), mock(EventPublisher.class), mock(IdentityClient.class),
            mock(com.hospio.tenant.client.PlatformClient.class));

    @BeforeEach
    void signIn() {
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(businesses.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hotels.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hotels.otherWithName(any(), any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Business business(Business.Kind kind) {
        Business b = Business.builder().name("QA Venue").timezone("Africa/Lagos").venueKind(kind).build();
        b.setId("b1");
        when(businesses.findById("b1")).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void accommodationIsAHotelOrAnyLocationWithRooms() {
        assertThat(VenueType.hasAccommodation(VenueType.HOTEL, 0)).isTrue();
        assertThat(VenueType.hasAccommodation(VenueType.LOUNGE, 0)).isFalse();
        assertThat(VenueType.hasAccommodation(VenueType.RESTAURANT, 0)).isFalse();
        assertThat(VenueType.hasAccommodation(VenueType.LOUNGE, 3)).as("a lounge that added rooms").isTrue();
        assertThat(VenueType.hasAccommodation((VenueType) null, 0)).as("older rows are hotels").isTrue();
        assertThat(VenueType.parse(null)).isEqualTo(VenueType.HOTEL);
        assertThat(VenueType.parse(" lounge ")).isEqualTo(VenueType.LOUNGE);
        assertThat(VenueType.parse("SPACESHIP")).isEqualTo(VenueType.HOTEL);
        assertThat(Business.Kind.MIXED.defaultVenue()).isEqualTo(VenueType.HOTEL);
        assertThat(Business.Kind.BAR.defaultVenue()).isEqualTo(VenueType.BAR);
    }

    @Test
    void aNewLocationTakesTheBusinessKindUnlessATypeIsSent() {
        business(Business.Kind.LOUNGE);
        HotelResponse lounge = service.addHotel(new CreateHotelRequest("QA Lounge Wuse", "Abuja", null, null, null, null, null, null, null, null, "b1"));
        assertThat(lounge.venueType()).isEqualTo(VenueType.LOUNGE);
        assertThat(lounge.hasAccommodation()).isFalse();

        HotelResponse hotel = service.addHotel(new CreateHotelRequest("QA Rooms Wuse", "Abuja", null, null, null, null, null, null, null, null, "b1",
                VenueType.HOTEL));
        assertThat(hotel.venueType()).isEqualTo(VenueType.HOTEL);
        assertThat(hotel.hasAccommodation()).isTrue();
    }

    @Test
    void messagesNameThePlaceByItsType() {
        business(Business.Kind.LOUNGE);
        when(hotels.otherWithName(any(), any(), any())).thenReturn(Optional.of("QA Lounge Wuse"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addHotel(
                        new CreateHotelRequest("QA Lounge Wuse", "Abuja", null, null, null, null, null, null, null, null, "b1")))
                .hasMessage("This business already has a location called QA Lounge Wuse. Give this one a different name");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addHotel(
                        new CreateHotelRequest(" ", "Abuja", null, null, null, null, null, null, null, null, "b1")))
                .hasMessageContaining("lounge name").hasMessageNotContaining("hotel");
        // A hotel reads what it always read.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addHotel(
                        new CreateHotelRequest("QA Lounge Wuse", "Abuja", null, null, null, null, null, null, null, null, "b1", VenueType.HOTEL)))
                .hasMessage("This business already has a hotel called QA Lounge Wuse. Give this one a different name");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addHotel(
                        new CreateHotelRequest(" ", "Abuja", null, null, null, null, null, null, null, null, "b1", VenueType.HOTEL)))
                .hasMessageContaining("hotel name");
    }

    @Test
    void anOlderBusinessStartsLocationsAsHotels() {
        business(Business.Kind.HOTEL);
        HotelResponse h = service.addHotel(new CreateHotelRequest("QA Hotel Ikeja", "Lagos", null, null, null, null, null, null, null, null, "b1"));
        assertThat(h.venueType()).isEqualTo(VenueType.HOTEL);
        assertThat(h.checkInTime()).isEqualTo("14:00");
    }

    @Test
    void theHandoverPackNamesALoungeByItsKindNeverZeroRooms() {
        HandoverPack pack = new HandoverPack("https://web-staging.hospio360.com");
        HotelResponse lounge = HotelResponse.of(hotel("h1", "QA Lounge Wuse Bar", VenueType.LOUNGE, 0));
        HotelResponse hotel = HotelResponse.of(hotel("h2", "QA Rooms Ikeja", VenueType.HOTEL, 12));
        String onlyLounge = pack.text("QA Lounge", "Ada", List.of(lounge), List.of());
        assertThat(onlyLounge).contains("LOCATIONS\n- QA Lounge Wuse Bar, Abuja · Lounge\n").doesNotContain("0 rooms").doesNotContain("HOTELS");
        String mixed = pack.text("QA Mixed", "Ada", List.of(hotel, lounge), List.of());
        assertThat(mixed).contains("LOCATIONS").contains("QA Rooms Ikeja, Abuja · 12 rooms").contains("QA Lounge Wuse Bar, Abuja · Lounge");
        String hotels = pack.text("QA Hotels", "Ada", List.of(hotel), List.of());
        assertThat(hotels).contains("HOTELS\n- QA Rooms Ikeja, Abuja · 12 rooms");
        assertThat(HandoverPack.where(HotelResponse.of(hotel("h3", "Lounge with rooms", VenueType.LOUNGE, 2)))).isEqualTo("Lounge · 2 rooms");
    }

    @Test
    void theWelcomeEmailAsksALoungeOwnerAboutOutletsNotRooms() {
        String lounge = OnboardingService.welcomeText("Ada Obi", "QA Lounge", "https://web-staging.hospio360.com", false);
        assertThat(lounge).contains("outlets, menus and staff").doesNotContain("rooms").doesNotContain("booking");
        assertThat(OnboardingService.welcomeText("Ada Obi", "QA Hotels", "https://x")).contains("rooms, prices and staff");
    }

    private static Hotel hotel(String id, String name, VenueType type, int rooms) {
        Hotel h = Hotel.builder().businessId("b1").name(name).city("Abuja").venueType(type).roomCount(rooms).build();
        h.setId(id);
        return h;
    }
}
