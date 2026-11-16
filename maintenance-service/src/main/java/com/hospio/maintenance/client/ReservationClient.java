package com.hospio.maintenance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Who is checked in to a room, so a room with a guest in it is never taken out of order by surprise. */
@Component
public class ReservationClient {

    /** The part of reservation-service's booking this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BookingRef(String guestName) {
    }

    private static final ParameterizedTypeReference<ApiResponse<BookingRef>> BOOKING = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    /** The checked-in guest's name, empty when nobody is in; 400 when reservation-service can't answer (never guess "empty"). */
    public Optional<String> guestIn(String roomId) {
        try {
            ApiResponse<BookingRef> body = restClient.get().uri("/internal/rooms/{id}/active-booking", roomId).retrieve().body(BOOKING);
            BookingRef d = body == null ? null : body.data();
            if (d == null) {
                return Optional.empty();
            }
            String name = d.guestName();
            return Optional.of(name == null || name.isBlank() ? "a guest" : name);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new BadRequestException("Can't check who is in the room right now · try again");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't check who is in the room right now · try again");
        }
    }
}
