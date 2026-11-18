package com.hospio.inventory.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** tenant-service's list of every hotel (GET /internal/hotels), for the default-stores backfill. */
@Component
public class TenantHotels {

    /** A hotel as far as the backfill needs it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HotelRef(String id, String businessId, String name) {
    }

    private static final ParameterizedTypeReference<ApiResponse<List<HotelRef>>> HOTELS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public TenantHotels(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String key) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, key);
    }

    protected TenantHotels() {
        this.restClient = null;
    }

    /** Every hotel; throws when tenant-service can't be reached (the backfill must not report "nothing to do" then). */
    public List<HotelRef> all() {
        ApiResponse<List<HotelRef>> body = restClient.get().uri("/internal/hotels").retrieve().body(HOTELS);
        return body == null || body.data() == null ? List.of() : body.data();
    }
}
