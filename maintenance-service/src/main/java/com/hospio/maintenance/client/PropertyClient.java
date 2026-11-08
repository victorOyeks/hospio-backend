package com.hospio.maintenance.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Takes a room out of order or puts it back, in property-service. */
@Slf4j
@Component
public class PropertyClient {

    private final RestClient restClient;

    public PropertyClient(@Value("${services.property.url}") String url, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public void outOfOrder(String roomId, String reason, LocalDate until) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        if (until != null) {
            body.put("until", until.toString());
        }
        restClient.post().uri("/internal/rooms/{id}/out-of-order", roomId).body(body).retrieve().toBodilessEntity();
    }

    public void backInService(String roomId) {
        restClient.post().uri("/internal/rooms/{id}/back-in-service", roomId).retrieve().toBodilessEntity();
    }
}
