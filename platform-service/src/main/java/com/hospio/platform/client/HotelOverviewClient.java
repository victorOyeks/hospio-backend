package com.hospio.platform.client;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.platform.dto.HotelFigures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The per-hotel figures the Hotels tab shows, gathered from each service's
 * {@code GET /internal/admin/hotel-overview?hotelId=}: property (rooms), reservation (occupancy, arrivals,
 * departures), folio (open folios), payment (payments today), hotel-config (WhatsApp, POS, setup completeness).
 * A service without the route yet, or one that fails, answers null.
 */
@Slf4j
@Component
public class HotelOverviewClient {

    private static final ParameterizedTypeReference<ApiResponse<HotelFigures.Property>> PROPERTY = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<HotelFigures.Reservation>> RESERVATION = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<HotelFigures.Folio>> FOLIO = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<HotelFigures.Payment>> PAYMENT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<HotelFigures.HotelConfig>> HOTEL_CONFIG = new ParameterizedTypeReference<>() {
    };

    private final RestClient property;
    private final RestClient reservation;
    private final RestClient folio;
    private final RestClient payment;
    private final RestClient hotelConfig;

    public HotelOverviewClient(@Value("${services.property.url}") String propertyUrl, @Value("${services.reservation.url}") String reservationUrl,
                               @Value("${services.folio.url}") String folioUrl, @Value("${services.payment.url}") String paymentUrl,
                               @Value("${services.hotelconfig.url}") String hotelConfigUrl,
                               JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.property = ServiceClients.system(propertyUrl, jwtUtil, internalApiKey);
        this.reservation = ServiceClients.system(reservationUrl, jwtUtil, internalApiKey);
        this.folio = ServiceClients.system(folioUrl, jwtUtil, internalApiKey);
        this.payment = ServiceClients.system(paymentUrl, jwtUtil, internalApiKey);
        this.hotelConfig = ServiceClients.system(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    public HotelFigures.Property property(String hotelId) {
        return overview("property", property, hotelId, PROPERTY);
    }

    public HotelFigures.Reservation reservation(String hotelId) {
        return overview("reservation", reservation, hotelId, RESERVATION);
    }

    public HotelFigures.Folio folio(String hotelId) {
        return overview("folio", folio, hotelId, FOLIO);
    }

    public HotelFigures.Payment payment(String hotelId) {
        return overview("payment", payment, hotelId, PAYMENT);
    }

    public HotelFigures.HotelConfig hotelConfig(String hotelId) {
        return overview("hotel-config", hotelConfig, hotelId, HOTEL_CONFIG);
    }

    /** The {@code data} object the service answered, or null. */
    private <T> T overview(String service, RestClient client, String hotelId, ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            ApiResponse<T> body = client.get().uri(b -> b.path("/internal/admin/hotel-overview").queryParam("hotelId", hotelId).build())
                    .header("X-Hotel-Id", hotelId).retrieve().body(type);
            return body == null ? null : body.data();
        } catch (Exception e) {
            if (e instanceof HttpClientErrorException.NotFound) {
                log.warn("{}-service has no GET /internal/admin/hotel-overview yet; hotel {} shows no {} figures", service, hotelId, service);
            } else {
                log.warn("{}-service hotel-overview for {} failed: {}", service, hotelId, e.getMessage());
            }
            return null;
        }
    }
}
