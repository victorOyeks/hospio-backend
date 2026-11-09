package com.hospio.finance.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    public record Contact(String id, String fullName, String email, String phone) {
    }

    public record Business(String id, String name, String ownerName, String ownerEmail, String ownerPhone) {
    }

    public record Hotel(String id, String name) {
    }

    public record Stay(String bookingId, String reference, String guestName, String roomNumber) {
    }

    public record Day(int arrivals, int arrivalsPending, int departures, int departuresPending, int inHouse, int noShows, int roomsOccupied,
                      List<Stay> pendingArrivals, List<Stay> pendingDepartures, List<Stay> departingToday) {
    }

    /** Open tabs and unsettled orders at one outlet. */
    public record OutletOpen(String outletId, String name, int openTabs, int unsettled) {
    }

    public record OpenOrders(int openTabs, int unsettled, List<OutletOpen> outlets) {
    }

    public record Owing(String bookingId, String guestName, String roomNumber, BigDecimal balance) {
    }

    // ---- identity -------------------------------------------------------------------------------------

    public Optional<Contact> contact(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            JsonNode d = data(identity.get().uri("/internal/users/{id}/contact", userId).retrieve().body(JsonNode.class));
            return d == null ? Optional.empty() : Optional.of(contact(d));
        } catch (Exception e) {
            log.warn("identity-service contact {} failed: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Contact> withRole(String hotelId, String role) {
        try {
            JsonNode d = data(identity.get().uri("/internal/users/with-role?hotelId={h}&role={r}", hotelId, role).retrieve().body(JsonNode.class));
            List<Contact> out = new ArrayList<>();
            if (d != null && d.isArray()) {
                d.forEach(n -> out.add(contact(n)));
            }
            return out;
        } catch (Exception e) {
            log.warn("identity-service with-role {} at {} failed: {}", role, hotelId, e.getMessage());
            return List.of();
        }
    }

    // ---- tenant ---------------------------------------------------------------------------------------

    public Optional<Business> business(String businessId) {
        try {
            JsonNode d = data(tenant.get().uri("/internal/businesses/{id}", businessId).retrieve().body(JsonNode.class));
            return d == null ? Optional.empty() : Optional.of(new Business(d.path("id").asText(null), d.path("name").asText(null), d.path("ownerName").asText(null),
                    d.path("ownerEmail").asText(null), d.path("ownerPhone").asText(null)));
        } catch (Exception e) {
            log.warn("tenant-service business {} failed: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Hotel> hotels(String businessId) {
        try {
            JsonNode d = data(tenant.get().uri("/internal/businesses/{id}/hotels", businessId).retrieve().body(JsonNode.class));
            List<Hotel> out = new ArrayList<>();
            if (d != null && d.isArray()) {
                d.forEach(n -> out.add(new Hotel(n.path("id").asText(), n.path("name").asText(null))));
            }
            return out;
        } catch (Exception e) {
            throw new ExternalServiceException("The hotels of this business can't be read right now", e);
        }
    }

    // ---- reservation ----------------------------------------------------------------------------------

    /** Throws when reservation-service can't answer: the night audit must not close on a guess. */
    public Day day(String hotelId, LocalDate date) {
        try {
            JsonNode d = data(reservation.get().uri("/internal/day-summary?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(JsonNode.class));
            if (d == null) {
                throw new ExternalServiceException("Reservations returned nothing for that day");
            }
            return new Day(d.path("arrivals").asInt(), d.path("arrivalsPending").asInt(), d.path("departures").asInt(), d.path("departuresPending").asInt(),
                    d.path("inHouse").asInt(), d.path("noShows").asInt(), d.path("roomsOccupied").asInt(), stays(d.path("pendingArrivals")),
                    stays(d.path("pendingDepartures")), stays(d.path("departingToday")));
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
            JsonNode body = reservation.post().uri("/internal/arrivals/no-show?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(JsonNode.class);
            return body == null ? 0 : body.path("data").asInt();
        } catch (Exception e) {
            throw new ExternalServiceException("The arrivals couldn't be marked as no-shows right now", e);
        }
    }

    // ---- property, folio, platform --------------------------------------------------------------------

    /** Rooms the hotel has, or -1 when property-service can't be reached. */
    public int roomCount(String hotelId) {
        try {
            JsonNode d = data(property.get().uri("/internal/rooms?hotelId={h}", hotelId).retrieve().body(JsonNode.class));
            return d == null ? 0 : d.size();
        } catch (Exception e) {
            log.warn("property-service rooms of {} failed: {}", hotelId, e.getMessage());
            return -1;
        }
    }

    public List<Owing> openBalances(String hotelId) {
        try {
            JsonNode d = data(folio.get().uri("/internal/folios/open-balances?hotelId={h}", hotelId).retrieve().body(JsonNode.class));
            List<Owing> out = new ArrayList<>();
            if (d != null && d.isArray()) {
                d.forEach(n -> out.add(new Owing(n.path("bookingId").asText(null), n.path("guestName").asText(null), n.path("roomNumber").asText(null),
                        new BigDecimal(n.path("balance").asText("0")))));
            }
            return out;
        } catch (Exception e) {
            throw new ExternalServiceException("Guest balances can't be read right now", e);
        }
    }

    // ---- fnb ------------------------------------------------------------------------------------------

    /** What the outlets sold on a day (fnb's day summary as it came), or empty when fnb-service can't be reached. */
    public Optional<JsonNode> outletSales(String hotelId, LocalDate date) {
        try {
            return Optional.ofNullable(data(fnb.get().uri("/internal/sales?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(JsonNode.class)));
        } catch (Exception e) {
            log.warn("fnb-service sales of {} on {} failed: {}", hotelId, date, e.getMessage());
            return Optional.empty();
        }
    }

    /** Open tabs and unsettled orders at the outlets, or empty when fnb-service can't be reached: the night audit must not close on a guess. */
    public Optional<OpenOrders> openOrders(String hotelId, LocalDate date) {
        try {
            JsonNode d = data(fnb.get().uri("/internal/orders/open?hotelId={h}&date={d}", hotelId, date.toString()).retrieve().body(JsonNode.class));
            if (d == null) {
                return Optional.empty();
            }
            List<OutletOpen> out = new ArrayList<>();
            d.path("outlets").forEach(n -> out.add(new OutletOpen(n.path("outletId").asText(null), n.path("name").asText(null), n.path("openTabs").asInt(), n.path("unsettled").asInt())));
            return Optional.of(new OpenOrders(d.path("openTabs").asInt(), d.path("unsettled").asInt(), out));
        } catch (Exception e) {
            log.warn("fnb-service open orders of {} failed: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Approvals waiting at the hotel, or null when platform-service can't be reached. */
    public Integer pendingApprovals(String hotelId) {
        try {
            JsonNode body = platform.get().uri("/internal/approvals/pending-count?hotelId={h}", hotelId).retrieve().body(JsonNode.class);
            return body == null ? null : body.path("data").asInt();
        } catch (Exception e) {
            log.warn("platform-service pending approvals of {} failed: {}", hotelId, e.getMessage());
            return null;
        }
    }

    private static List<Stay> stays(JsonNode array) {
        List<Stay> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> out.add(new Stay(n.path("bookingId").asText(null), n.path("reference").asText(null), n.path("guestName").asText(null), n.path("roomNumber").asText(null))));
        }
        return out;
    }

    private static Contact contact(JsonNode n) {
        return new Contact(n.path("id").asText(null), n.path("fullName").asText(null), n.path("email").asText(null), n.path("phone").asText(null));
    }

    private static JsonNode data(JsonNode body) {
        JsonNode d = body == null ? null : body.path("data");
        return d == null || d.isMissingNode() || d.isNull() ? null : d;
    }
}
