package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
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

    /** Empty when there is no such booking at the caller's hotel; throws when reservation-service can't be reached. */
    public Optional<Booking> booking(String id) {
        try {
            JsonNode body = restClient.get().uri("/bookings/{id}", id).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode() || d.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new Booking(t(d, "id"), t(d, "hotelId"), t(d, "reference"), t(d, "guestId"), t(d, "guestName"), t(d, "guestEmail"),
                    t(d, "guestPhone"), t(d, "roomNumber"), t(d, "roomTypeName"), day(d, "checkInDate"), day(d, "checkOutDate"), t(d, "status")));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new ExternalServiceException("Bookings can't be read right now; try again in a moment");
        }
    }

    private static String t(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static LocalDate day(JsonNode n, String f) {
        String s = t(n, f);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }
}
