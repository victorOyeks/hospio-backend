package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** A booking's status and hotel from reservation-service, so a payment is never taken against a cancelled booking. */
@Slf4j
@Component
public class ReservationClient {

    public record BookingState(String status, String hotelId) {
    }

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String reservationUrl, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(reservationUrl, jwtUtil, internalApiKey);
    }

    /** Empty when the booking doesn't exist; {@code null} status when reservation-service can't be reached (callers let the payment through). */
    public Optional<BookingState> booking(String bookingId) {
        try {
            JsonNode body = restClient.get().uri("/internal/bookings/{id}", bookingId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new BookingState(data.path("status").asText(null), data.path("hotelId").asText(null)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read booking {} from reservation-service: {}", bookingId, e.getMessage());
            return Optional.of(new BookingState(null, null));
        }
    }
}
