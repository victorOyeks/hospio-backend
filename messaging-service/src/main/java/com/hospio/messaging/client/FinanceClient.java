package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Today's rota at the caller's hotel (finance-service workforce), for "message everyone on shift". */
@Component
public class FinanceClient {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final RestClient restClient;

    public FinanceClient(@Value("${services.finance.url}") String url, JwtUtil jwtUtil,
                         @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record OnShift(String userId, String name, String role, String startsAt, String endsAt, boolean clockedIn) {
    }

    /** One row per person on today's rota (Lagos day, cancelled shifts left out), earliest start first. */
    public List<OnShift> onShiftToday() {
        LocalDate today = LocalDate.now(LAGOS);
        JsonNode body;
        try {
            body = restClient.get().uri(b -> b.path("/workforce/rota").queryParam("from", today).queryParam("to", today).build())
                    .retrieve().body(JsonNode.class);
        } catch (RuntimeException e) {
            throw new ExternalServiceException("Today's rota can't be read right now; try again in a moment");
        }
        Map<String, OnShift> byUser = new LinkedHashMap<>();
        JsonNode d = body == null ? null : body.path("data");
        if (d != null && d.isArray()) {
            for (JsonNode n : d) {
                String userId = n.path("userId").asText(null);
                if (userId == null || "CANCELLED".equals(n.path("status").asText())) {
                    continue;
                }
                byUser.putIfAbsent(userId, new OnShift(userId, n.path("userName").asText(""), n.path("role").asText(null),
                        hm(n.path("startsAt").asText(null)), hm(n.path("endsAt").asText(null)), n.path("clockedIn").asBoolean(false)));
            }
        }
        return new ArrayList<>(byUser.values());
    }

    private static String hm(String t) {
        return t == null ? null : t.length() >= 5 ? t.substring(0, 5) : t;
    }
}
