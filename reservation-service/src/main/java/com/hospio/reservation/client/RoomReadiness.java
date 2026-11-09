package com.hospio.reservation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Is the room cleaned? Housekeeping's room board, plus the hotel's HOUSEKEEPING.inspectDepartures rule. Fails open. */
@Slf4j
@Component
public class RoomReadiness {

    private final RestClient housekeeping;
    private final HotelSettingsReader settings;

    public RoomReadiness(@Value("${services.housekeeping.url}") String housekeepingUrl, @Value("${services.hotelconfig.url}") String hotelConfigUrl,
                         JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.housekeeping = ServiceClients.internal(housekeepingUrl, jwtUtil, internalApiKey);
        this.settings = new HotelSettingsReader(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    /** True when housekeeping says the room is DIRTY. A room with no status, or an unreachable housekeeping, is not dirty. */
    public boolean isDirty(String hotelId, String roomId) {
        try {
            JsonNode body = housekeeping.get().uri("/internal/room-status?hotelId={h}", hotelId).retrieve().body(JsonNode.class);
            if (body != null) {
                for (JsonNode r : body.path("data")) {
                    if (roomId.equals(r.path("roomId").asText())) {
                        return "DIRTY".equals(r.path("status").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read the room board for hotel {}: {}", hotelId, e.getMessage());
        }
        return false;
    }

    /** HOUSEKEEPING.inspectDepartures: when on, a dirty room needs a manager's override to check in. */
    public boolean inspectDepartures(String hotelId) {
        return settings.section(hotelId, SetupCatalogue.HOUSEKEEPING).map(n -> n.path("inspectDepartures").asBoolean(true)).orElse(true);
    }
}
