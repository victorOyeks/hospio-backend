package com.hospio.finance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.finance.dto.OutletSalesSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Everything finance asks the other services for: who a person is (identity), a business and its hotels (tenant), the day's
 * stays (reservation), rooms (property), what guests owe (folio) and how many approvals wait (platform). Every call acts as the
 * system with the hotel passed explicitly, so it works the same from a request, a listener or the owner's multi-hotel summary.
 */
@Slf4j
@Component
public class PeerServices {

    private final RestClient identity;
    private final RestClient tenant;
    private final RestClient reservation;
    private final RestClient property;
    private final RestClient folio;
    private final RestClient platform;
    private final RestClient fnb;

    public PeerServices(@Value("${services.identity.url}") String identityUrl, @Value("${services.tenant.url}") String tenantUrl,
                        @Value("${services.reservation.url}") String reservationUrl, @Value("${services.property.url}") String propertyUrl,
                        @Value("${services.folio.url}") String folioUrl, @Value("${services.platform.url}") String platformUrl, @Value("${services.fnb.url}") String fnbUrl,
                        JwtUtil jwtUtil, @Value("${services.internal.api-key}") String key) {
        this.identity = ServiceClients.system(identityUrl, jwtUtil, key);
        this.tenant = ServiceClients.system(tenantUrl, jwtUtil, key);
        this.reservation = ServiceClients.system(reservationUrl, jwtUtil, key);
        this.property = ServiceClients.system(propertyUrl, jwtUtil, key);
        this.folio = ServiceClients.system(folioUrl, jwtUtil, key);
        this.platform = ServiceClients.system(platformUrl, jwtUtil, key);
        this.fnb = ServiceClients.system(fnbUrl, jwtUtil, key);
    }

    private static final ParameterizedTypeReference<ApiResponse<Contact>> CONTACT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<Contact>>> CONTACTS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<Business>> BUSINESS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<Hotel>>> HOTELS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<Day>> DAY = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<Integer>> COUNT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<RoomRef>>> ROOMS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<Owing>>> OWING = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<OutletSalesSummary>> SALES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<OpenOrders>> OPEN_ORDERS = new ParameterizedTypeReference<>() {
    };

    /** identity's UserContactResponse, the fields finance uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(String id, String fullName, String email, String phone) {
    }

    /** tenant's BusinessResponse, the fields finance uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Business(String id, String name, String ownerName, String ownerEmail, String ownerPhone) {
    }

    /** tenant's HotelResponse, the fields finance uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hotel(String id, String name, Boolean hasAccommodation) {
        public Hotel(String id, String name) {
            this(id, name, null);
        }
    }

    /** One stay in reservation's DaySummary. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stay(String bookingId, String reference, String guestName, String roomNumber) {
    }

    /** reservation's DaySummary; a missing list reads as empty. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Day(int arrivals, int arrivalsPending, int departures, int departuresPending, int inHouse, int noShows, int roomsOccupied,
                      List<Stay> pendingArrivals, List<Stay> pendingDepartures, List<Stay> departingToday) {
        public Day {
            pendingArrivals = pendingArrivals == null ? List.of() : pendingArrivals;
            pendingDepartures = pendingDepartures == null ? List.of() : pendingDepartures;
            departingToday = departingToday == null ? List.of() : departingToday;
        }
    }

    /** Open tabs and unsettled orders at one outlet (fnb's OpenOrdersSummary.OutletOpen). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutletOpen(String outletId, String name, int openTabs, int unsettled, List<OpenOrder> orders) {
        public OutletOpen {
            orders = orders == null ? List.of() : orders;
        }

        public OutletOpen(String outletId, String name, int openTabs, int unsettled) {
            this(outletId, name, openTabs, unsettled, List.of());
        }
    }

    /** One open tab or unsettled order: its number, kind (TAB, DINE_IN, ROOM_SERVICE…), where it is and its total. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenOrder(String number, String kind, String tableNo, String roomNumber, BigDecimal total) {
    }

    /** fnb's OpenOrdersSummary; a missing outlet list reads as empty. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenOrders(int openTabs, int unsettled, List<OutletOpen> outlets) {
        public OpenOrders {
            outlets = outlets == null ? List.of() : outlets;
        }
    }

    /** folio's OpenBalance, the fields finance uses; a missing balance reads as zero. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owing(String bookingId, String guestName, String roomNumber, BigDecimal balance) {
        public Owing {
            balance = balance == null ? BigDecimal.ZERO : balance;
        }
    }

    /** One of property's rooms: only counted. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomRef(String id) {
    }

    // ---- identity -------------------------------------------------------------------------------------

    public Optional<Contact> contact(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(data(identity.get().uri("/internal/users/{id}/contact", userId).retrieve().body(CONTACT)));
        } catch (Exception e) {
            log.warn("identity-service contact {} failed: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Contact> withRole(String hotelId, String role) {
        try {
            List<Contact> d = data(identity.get().uri("/internal/users/with-role?hotelId={h}&role={r}", hotelId, role).retrieve().body(CONTACTS));
            return d == null ? new ArrayList<>() : new ArrayList<>(d);
        } catch (Exception e) {
            log.warn("identity-service with-role {} at {} failed: {}", role, hotelId, e.getMessage());
            return List.of();
        }
    }

    // ---- tenant ---------------------------------------------------------------------------------------

    public Optional<Business> business(String businessId) {
        try {
            return Optional.ofNullable(data(tenant.get().uri("/internal/businesses/{id}", businessId).retrieve().body(BUSINESS)));
        } catch (Exception e) {
            log.warn("tenant-service business {} failed: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Hotel> hotels(String businessId) {
        try {
            List<Hotel> d = data(tenant.get().uri("/internal/businesses/{id}/hotels", businessId).retrieve().body(HOTELS));
            List<Hotel> out = new ArrayList<>();
            if (d != null) {
                d.forEach(h -> out.add(new Hotel(h.id() == null ? "" : h.id(), h.name(), h.hasAccommodation())));
            }
            return out;
        } catch (Exception e) {
            throw new ExternalServiceException("The " + com.hospio.common.venue.VenuePlaces.business().places() + " of this business can't be read right now", e);
        }
    }

    private static final org.springframework.core.ParameterizedTypeReference<com.hospio.common.response.ApiResponse<Hotel>> HOTEL =
            new org.springframework.core.ParameterizedTypeReference<>() {
            };

    /**
     * Whether the location has accommodation (a hotel, or any location with rooms; docs/plan-venue-type.md). True when tenant-service
     * can't be asked: the night audit then keeps its arrival and departure checks rather than dropping them on a guess.
     */
    public boolean hasAccommodation(String hotelId) {
        try {
            Hotel h = data(tenant.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL));
            return h == null || h.hasAccommodation() == null || h.hasAccommodation();
        } catch (Exception e) {
            log.warn("tenant-service hotel {} failed: {}", hotelId, e.getMessage());
            return true;
        }
    }

    // ---- reservation ----------------------------------------------------------------------------------

    /** Throws when reservation-service can't answer: the night audit must not close on a guess. */
    public Day day(String hotelId, LocalDate date) {
        try {
            Day d = data(reservation.get().uri("/internal/day-summary?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(DAY));
            if (d == null) {
                throw new ExternalServiceException("Reservations returned nothing for that day");
            }
            return d;
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("Reservations can't be read right now", e);
        }
    }

    public Optional<Day> daySoft(String hotelId, LocalDate date) {
        try {
            return Optional.of(day(hotelId, date));
        } catch (RuntimeException e) {
            log.warn("Day summary for hotel {} unavailable: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    public int markNoShows(String hotelId, LocalDate date) {
        try {
            Integer marked = data(reservation.post().uri("/internal/arrivals/no-show?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(COUNT));
            return marked == null ? 0 : marked;
        } catch (Exception e) {
            throw new ExternalServiceException("The arrivals couldn't be marked as no-shows right now", e);
        }
    }

    // ---- property, folio, platform --------------------------------------------------------------------

    /** Rooms the hotel has, or -1 when property-service can't be reached. */
    public int roomCount(String hotelId) {
        try {
            List<RoomRef> d = data(property.get().uri("/internal/rooms?hotelId={h}", hotelId).retrieve().body(ROOMS));
            return d == null ? 0 : d.size();
        } catch (Exception e) {
            log.warn("property-service rooms of {} failed: {}", hotelId, e.getMessage());
            return -1;
        }
    }

    public List<Owing> openBalances(String hotelId) {
        try {
            List<Owing> d = data(folio.get().uri("/internal/folios/open-balances?hotelId={h}", hotelId).retrieve().body(OWING));
            return d == null ? new ArrayList<>() : new ArrayList<>(d);
        } catch (Exception e) {
            throw new ExternalServiceException("Guest balances can't be read right now", e);
        }
    }

    // ---- fnb ------------------------------------------------------------------------------------------

    /** What the outlets sold on a day (fnb's day summary), or empty when fnb-service can't be reached. */
    public Optional<OutletSalesSummary> outletSales(String hotelId, LocalDate date) {
        try {
            return Optional.ofNullable(data(fnb.get().uri("/internal/sales?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(SALES)));
        } catch (Exception e) {
            log.warn("fnb-service sales of {} on {} failed: {}", hotelId, date, e.getMessage());
            return Optional.empty();
        }
    }

    /** Open tabs and unsettled orders at the outlets, or empty when fnb-service can't be reached: the night audit must not close on a guess. */
    public Optional<OpenOrders> openOrders(String hotelId, LocalDate date) {
        try {
            return Optional.ofNullable(data(fnb.get().uri("/internal/orders/open?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(OPEN_ORDERS)));
        } catch (Exception e) {
            log.warn("fnb-service open orders of {} failed: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Approvals waiting at the hotel, or null when platform-service can't be reached. */
    public Integer pendingApprovals(String hotelId) {
        try {
            ApiResponse<Integer> body = platform.get().uri("/internal/approvals/pending-count?hotelId={h}", hotelId).retrieve().body(COUNT);
            return body == null ? null : body.data() == null ? 0 : body.data();
        } catch (Exception e) {
            log.warn("platform-service pending approvals of {} failed: {}", hotelId, e.getMessage());
            return null;
        }
    }

    private static <T> T data(ApiResponse<T> body) {
        return body == null ? null : body.data();
    }
}
