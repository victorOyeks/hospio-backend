package com.hospio.messaging.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Today's rota at the caller's hotel (finance-service workforce), for "message everyone on shift". */
@Component
public class FinanceClient {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    /** A shift on the rota as finance-service's GET /workforce/rota gives it (WorkforceViews.ShiftView), the fields read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Shift(String userId, String userName, String role, LocalTime startsAt, LocalTime endsAt, String status, boolean clockedIn) {
    }

    private static final ParameterizedTypeReference<ApiResponse<List<Shift>>> ROTA = new ParameterizedTypeReference<>() {
    };

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
        ApiResponse<List<Shift>> body;
        try {
            body = restClient.get().uri(b -> b.path("/workforce/rota").queryParam("from", today).queryParam("to", today).build())
                    .retrieve().body(ROTA);
        } catch (RuntimeException e) {
            throw new ExternalServiceException("Today's rota can't be read right now; try again in a moment");
        }
        Map<String, OnShift> byUser = new LinkedHashMap<>();
        List<Shift> rota = body == null || body.data() == null ? List.of() : body.data();
        for (Shift n : rota) {
            if (n == null || n.userId() == null || "CANCELLED".equals(n.status())) {
                continue;
            }
            byUser.putIfAbsent(n.userId(), new OnShift(n.userId(), n.userName() == null ? "" : n.userName(), n.role(),
                    hm(n.startsAt()), hm(n.endsAt()), n.clockedIn()));
        }
        return new ArrayList<>(byUser.values());
    }

    /** "08:00" (hours and minutes). */
    private static String hm(LocalTime t) {
        return t == null ? null : t.toString().substring(0, 5);
    }
}
