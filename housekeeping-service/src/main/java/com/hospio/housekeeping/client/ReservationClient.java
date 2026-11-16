package com.hospio.housekeeping.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Who is in a room, so minibar and laundry can be charged to the stay. */
@Slf4j
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record ActiveStay(String bookingId, String guestName, String roomNumber) {
    }

    /** The part of reservation-service's booking this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BookingRef(String id, String guestName, String roomNumber, String roomId) {
    }

    private static final ParameterizedTypeReference<ApiResponse<BookingRef>> BOOKING = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<BookingRef>>> BOOKINGS = new ParameterizedTypeReference<>() {
    };

    public Optional<ActiveStay> activeStay(String roomId) {
        try {
            ApiResponse<BookingRef> body = restClient.get().uri("/internal/rooms/{id}/active-booking", roomId).retrieve().body(BOOKING);
            BookingRef d = body == null ? null : body.data();
            if (d == null) {
                return Optional.empty();
            }
            return Optional.of(new ActiveStay(text(d.id()), text(d.guestName()), text(d.roomNumber())));
        } catch (Exception e) {
            log.debug("No active stay for room {}: {}", roomId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Room ids that have a BOOKED arrival on {@code date}; empty when reservation-service can't be reached. */
    public Set<String> roomsWithArrivalOn(String hotelId, LocalDate date) {
        Set<String> rooms = new HashSet<>();
        try {
            ApiResponse<List<BookingRef>> body = restClient.get().uri("/internal/arrivals?hotelId={h}&date={d}", hotelId, date.toString())
                    .retrieve().body(BOOKINGS);
            if (body != null && body.data() != null) {
                for (BookingRef b : body.data()) {
                    if (b != null && b.roomId() != null) {
                        rooms.add(b.roomId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read arrivals for hotel {} on {}: {}", hotelId, date, e.getMessage());
        }
        return rooms;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public ActiveStay requireActiveStay(String roomId) {
        return activeStay(roomId).orElseThrow(() -> new BadRequestException("Nobody is checked in to that room"));
    }
}
