package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.messaging.mail.EmailTemplates;
import java.time.Duration;
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

    private final RestClient restClient;
    /** At most 10,000 hotels; a failed read falls back to Hospio360's look and is retried after 30 seconds. */
    private final TtlCache<EmailTemplates.Brand> cache = new TtlCache<>(10_000, TTL, Duration.ofSeconds(30));

    public HotelBrandClient(@Value("${services.hotelconfig.url}") String hotelConfigUrl, JwtUtil jwtUtil,
                            @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = com.hospio.common.security.client.ServiceClients.system(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    public EmailTemplates.Brand brand(String hotelId) {
        if (hotelId == null || hotelId.isBlank()) {
            return EmailTemplates.Brand.HOSPIO;
        }
        return cache.get(hotelId, () -> {
            try {
                JsonNode body = restClient.get().uri("/internal/settings/BRAND?hotelId={id}", hotelId).retrieve().body(JsonNode.class);
                JsonNode d = body == null ? null : body.path("data");
                return TtlCache.Loaded.ok(d != null && d.isObject() ? EmailTemplates.Brand.of(d.path("primaryColour").asText(null), d.path("logoUrl").asText(null))
                        : EmailTemplates.Brand.HOSPIO);
            } catch (Exception e) {
                log.warn("Could not read hotel {}'s brand from hotel-config: {}", hotelId, e.getMessage());
                return TtlCache.Loaded.failed(EmailTemplates.Brand.HOSPIO);
            }
        });
    }
}
