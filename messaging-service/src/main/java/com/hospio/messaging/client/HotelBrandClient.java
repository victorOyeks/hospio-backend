package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.messaging.mail.EmailTemplates;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A hotel's brand for its emails (docs/email.md): Hotel setup → Brand's colour, short name and logo, read from
 * hotel-config-service and kept for 10 minutes. A hotel with nothing set, or one that can't be read, gets Hospio360's own
 * look ({@link EmailTemplates.Brand#HOSPIO}).
 */
@Slf4j
@Component
public class HotelBrandClient {

    private static final Duration TTL = Duration.ofMinutes(10);

    private record Cached(EmailTemplates.Brand brand, Instant at) {
    }

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public HotelBrandClient(@Value("${services.hotelconfig.url}") String hotelConfigUrl, JwtUtil jwtUtil,
                            @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = com.hospio.common.security.client.ServiceClients.system(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    public EmailTemplates.Brand brand(String hotelId) {
        if (hotelId == null || hotelId.isBlank()) {
            return EmailTemplates.Brand.HOSPIO;
        }
        Cached c = cache.get(hotelId);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) {
            return c.brand();
        }
        EmailTemplates.Brand brand = EmailTemplates.Brand.HOSPIO;
        try {
            JsonNode body = restClient.get().uri("/internal/settings/BRAND?hotelId={id}", hotelId).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d != null && d.isObject()) {
                brand = EmailTemplates.Brand.of(d.path("primaryColour").asText(null), d.path("logoUrl").asText(null));
            }
        } catch (Exception e) {
            log.warn("Could not read hotel {}'s brand from hotel-config: {}", hotelId, e.getMessage());
        }
        cache.put(hotelId, new Cached(brand, Instant.now()));
        return brand;
    }
}
