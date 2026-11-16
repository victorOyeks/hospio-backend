package com.hospio.fnb.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Who is checked in to a room, so a bar or kitchen order can be charged to it. */
@Slf4j
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record ActiveStay(String bookingId, String guestName, String roomNumber, String paymentPlan, String organizationId) {
    }

    /** The part of reservation-service's booking this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BookingRef(String id, String guestName, String roomNumber, String paymentPlan, String organizationId) {

        ActiveStay stay() {
            return new ActiveStay(text(id), text(guestName), text(roomNumber), paymentPlan, organizationId);
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }

    private static final ParameterizedTypeReference<ApiResponse<BookingRef>> BOOKING = new ParameterizedTypeReference<>() {
    };

    /** The current stay in the room, or empty when nobody is checked in (or the lookup fails to find one). */
    public java.util.Optional<ActiveStay> findActiveStay(String roomId) {
        try {
            BookingRef d = activeBooking(roomId);
            if (d == null || d.id() == null || d.id().isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(d.stay());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return java.util.Optional.empty();
            }
            throw new BadRequestException("Can't check that room right now");
        }
    }

    public ActiveStay activeStay(String roomId) {
        try {
            BookingRef d = activeBooking(roomId);
            if (d == null) {
                throw new BadRequestException("Nobody is checked in to that room");
            }
            return d.stay();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BadRequestException("Nobody is checked in to that room");
            }
            throw new BadRequestException("Can't check that room right now");
        }
    }

    private BookingRef activeBooking(String roomId) {
        ApiResponse<BookingRef> body = restClient.get().uri("/internal/rooms/{id}/active-booking", roomId).retrieve().body(BOOKING);
        return body == null ? null : body.data();
    }
}
