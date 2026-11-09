package com.hospio.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The per-hotel figures the Hotels tab shows, gathered from each service's
 * {@code GET /internal/admin/hotel-overview?hotelId=}: property (rooms), reservation (occupancy, arrivals,
 * departures), folio (open folios), payment (payments today), hotel-config (WhatsApp, POS, setup completeness).
 * A service without the route yet answers null.
 */
@Slf4j
@Component
public class HotelOverviewClient {

    private final Map<String, RestClient> clients = new LinkedHashMap<>();

    public HotelOverviewClient(@Value("${services.property.url}") String propertyUrl, @Value("${services.reservation.url}") String reservationUrl,
                               @Value("${services.folio.url}") String folioUrl, @Value("${services.payment.url}") String paymentUrl,
                               @Value("${services.hotelconfig.url}") String hotelConfigUrl,
                               JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        clients.put("property", ServiceClients.system(propertyUrl, jwtUtil, internalApiKey));
        clients.put("reservation", ServiceClients.system(reservationUrl, jwtUtil, internalApiKey));
        clients.put("folio", ServiceClients.system(folioUrl, jwtUtil, internalApiKey));
        clients.put("payment", ServiceClients.system(paymentUrl, jwtUtil, internalApiKey));
        clients.put("hotel-config", ServiceClients.system(hotelConfigUrl, jwtUtil, internalApiKey));
    }

    public java.util.Set<String> services() {
        return clients.keySet();
    }

    /** The {@code data} object the service answered, or null. */
    public JsonNode overview(String service, String hotelId) {
        RestClient client = clients.get(service);
        if (client == null) {
            return null;
        }
        try {
            JsonNode body = client.get().uri(b -> b.path("/internal/admin/hotel-overview").queryParam("hotelId", hotelId).build())
                    .header("X-Hotel-Id", hotelId).retrieve().body(JsonNode.class);
            return TenantClient.data(body);
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
