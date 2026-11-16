package com.hospio.reservation.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupSection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Is the room cleaned? Housekeeping's room board, plus the hotel's HOUSEKEEPING.inspectDepartures rule. Fails open. */
@Slf4j
@Component
public class RoomReadiness {

    /** One room on housekeeping's board: its id and status (CLEAN, DIRTY, ...). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomStatus(String roomId, String status) {
    }

    private static final ParameterizedTypeReference<ApiResponse<List<RoomStatus>>> BOARD = new ParameterizedTypeReference<>() {
    };

    private final RestClient housekeeping;
    private final HotelSettingsReader settings;

    public RoomReadiness(@Value("${services.housekeeping.url}") String housekeepingUrl, @Value("${services.hotelconfig.url}") String hotelConfigUrl,
                         JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.housekeeping = ServiceClients.internal(housekeepingUrl, jwtUtil, internalApiKey);
        this.settings = new HotelSettingsReader(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    /**
     * True when housekeeping says the room is DIRTY. A room with no status, or an unreachable housekeeping, is not dirty. Called before
     * check-in opens its transaction, never while a lock is held.
     * TODO housekeeping has no single-room read yet: this downloads the hotel's whole board to find one room. {@code roomId} is sent
     * already so housekeeping can answer just that room once /internal/room-status accepts it (the loop below still picks the room out).
     */
    public boolean isDirty(String hotelId, String roomId) {
        try {
            ApiResponse<List<RoomStatus>> body = housekeeping.get().uri("/internal/room-status?hotelId={h}&roomId={r}", hotelId, roomId)
                    .retrieve().body(BOARD);
            if (body != null && body.data() != null) {
                for (RoomStatus r : body.data()) {
                    if (r != null && roomId.equals(r.roomId())) {
                        return "DIRTY".equals(r.status());
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
        return settings.section(hotelId, SetupSection.Housekeeping.class).map(SetupSection.Housekeeping::inspectDepartures).orElse(true);
    }
}
