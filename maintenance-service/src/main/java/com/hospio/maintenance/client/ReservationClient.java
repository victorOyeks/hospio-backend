package com.hospio.maintenance.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Who is checked in to a room, so a room with a guest in it is never taken out of order by surprise. */
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    /** The checked-in guest's name, empty when nobody is in; 400 when reservation-service can't answer (never guess "empty"). */
    public Optional<String> guestIn(String roomId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms/{id}/active-booking", roomId).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode() || d.isNull()) {
                return Optional.empty();
            }
            String name = d.path("guestName").asText("");
            return Optional.of(name.isBlank() ? "a guest" : name);
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
