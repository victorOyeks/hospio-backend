package com.hospio.fnb.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    public ActiveStay activeStay(String roomId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms/{id}/active-booking", roomId).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode()) {
                throw new BadRequestException("Nobody is checked in to that room");
            }
            return new ActiveStay(d.path("id").asText(), d.path("guestName").asText(), d.path("roomNumber").asText(),
                    d.path("paymentPlan").asText(null), d.path("organizationId").asText(null));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BadRequestException("Nobody is checked in to that room");
            }
            throw new BadRequestException("Can't check that room right now");
        }
    }
}
