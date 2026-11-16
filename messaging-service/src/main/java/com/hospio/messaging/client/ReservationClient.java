package com.hospio.messaging.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** A booking as the caller sees it (their token and hotel are forwarded, so another hotel's booking is not found). */
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record Booking(String id, String hotelId, String reference, String guestId, String guestName, String guestEmail, String guestPhone,
                          String roomNumber, String roomType, LocalDate checkIn, LocalDate checkOut, String status) {
    }

    /** A booking as reservation-service's GET /bookings/{id} gives it (BookingResponse), the fields read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BookingView(String id, String hotelId, String reference, String guestId, String guestName, String guestEmail, String guestPhone,
                       String roomNumber, String roomTypeName, LocalDate checkInDate, LocalDate checkOutDate, String status) {
    }

    private static final ParameterizedTypeReference<ApiResponse<BookingView>> BOOKING = new ParameterizedTypeReference<>() {
    };

    /** Empty when there is no such booking at the caller's hotel; throws when reservation-service can't be reached. */
    public Optional<Booking> booking(String id) {
        try {
            ApiResponse<BookingView> body = restClient.get().uri("/bookings/{id}", id).retrieve().body(BOOKING);
            BookingView d = body == null ? null : body.data();
            if (d == null) {
                return Optional.empty();
            }
            return Optional.of(new Booking(t(d.id()), t(d.hotelId()), t(d.reference()), t(d.guestId()), t(d.guestName()), t(d.guestEmail()),
                    t(d.guestPhone()), t(d.roomNumber()), t(d.roomTypeName()), d.checkInDate(), d.checkOutDate(), t(d.status())));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new ExternalServiceException("Bookings can't be read right now; try again in a moment");
        }
    }

    private static String t(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
