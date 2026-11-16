package com.hospio.fnb.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Room numbers, so a refusal can say "Room 204" rather than an id. */
@Component
public class PropertyClient {

    /** The part of property-service's room this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomRef(String number) {
    }

    private static final ParameterizedTypeReference<ApiResponse<RoomRef>> ROOM = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public PropertyClient(@Value("${services.property.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    /** The room's number, or null when property-service can't say. */
    public String roomNumber(String roomId) {
        try {
            ApiResponse<RoomRef> body = restClient.get().uri("/internal/rooms/{id}", roomId).retrieve().body(ROOM);
            return body == null || body.data() == null ? null : body.data().number();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
