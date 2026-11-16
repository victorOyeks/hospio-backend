package com.hospio.reservation.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The hotel's public face for the guest's booking page: name, where it is, how to reach it, check-in and check-out times. */
@Slf4j
@Component
public class TenantHotelClient {

    public record HotelCard(String name, String address, String city, String phone, String email, String checkInTime, String checkOutTime) {
    }

    /** A hotel as tenant-service's GET /internal/hotels/{id} gives it (HotelResponse), the fields read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hotel(String name, String address, String city, String phone, String email, String checkInTime, String checkOutTime) {
    }

    private static final ParameterizedTypeReference<ApiResponse<Hotel>> HOTEL = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public TenantHotelClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    /** Empty when tenant-service can't be reached: the page then shows the booking without the hotel's details. */
    public Optional<HotelCard> hotel(String hotelId) {
        if (hotelId == null) {
            return Optional.empty();
        }
        try {
            ApiResponse<Hotel> body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            Hotel d = body == null ? null : body.data();
            if (d == null || d.name() == null) {
                return Optional.empty();
            }
            return Optional.of(new HotelCard(d.name(), text(d.address()), text(d.city()), text(d.phone()), text(d.email()),
                    text(d.checkInTime()), text(d.checkOutTime())));
        } catch (Exception e) {
            log.warn("Could not read hotel {} from tenant-service: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
