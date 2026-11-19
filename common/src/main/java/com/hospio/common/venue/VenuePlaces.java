package com.hospio.common.venue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * The {@link VenueWording} for a location or a business, asked from tenant-service ({@code /internal/hotels/{id}},
 * {@code /internal/businesses/{id}}) and cached {@value #TTL_MS} ms. Used where a message names the place: "This lounge doesn't take
 * cash", "Lounge setup".
 *
 * <p>Always answers: when the type is unknown, tenant-service can't be reached, or nothing is configured (unit tests, tenant-service
 * itself, which reads its own rows), the words are a hotel's, i.e. what every message said before venue types. Messages are built
 * through the static calls so that services and their tests keep their constructors.
 */
@Slf4j
@Component
public class VenuePlaces {

    static final long TTL_MS = 300_000L;
    private static final long FAILURE_TTL_MS = 30_000L;
    private static volatile VenuePlaces instance;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HotelRef(String venueType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessRef(String venueKind, List<HotelRef> hotels) {
    }

    private record Cached(VenueWording words, long until) {
    }

    private static final ParameterizedTypeReference<ApiResponse<HotelRef>> HOTEL = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<BusinessRef>> BUSINESS = new ParameterizedTypeReference<>() {
    };

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private RestClient tenant;

    @Autowired
    public void configure(@Value("${services.tenant.url:}") String tenantUrl, @Value("${services.internal.api-key:}") String internalApiKey,
                          JwtUtil jwtUtil) {
        this.tenant = StringUtils.hasText(tenantUrl) ? ServiceClients.system(tenantUrl, jwtUtil, internalApiKey) : null;
        instance = this;
    }

    /** The words for the location in context (the X-Hotel-Id of the request); a hotel's when there is none. */
    public static VenueWording here() {
        return at(TenantContext.current().map(Tenant::hotelId).orElse(null));
    }

    /** The words for this location. */
    public static VenueWording at(String hotelId) {
        VenuePlaces p = instance;
        return p == null || hotelId == null ? VenueWording.HOTEL : p.cached("h|" + hotelId, () -> p.fetchHotel(hotelId));
    }

    /** Business level, for the business in context: "hotel" while all its locations are hotels, else "location". */
    public static VenueWording business() {
        return ofBusiness(TenantContext.current().map(Tenant::businessId).orElse(null));
    }

    public static VenueWording ofBusiness(String businessId) {
        VenuePlaces p = instance;
        return p == null || businessId == null ? VenueWording.HOTEL : p.cached("b|" + businessId, () -> p.fetchBusiness(businessId));
    }

    private VenueWording cached(String key, java.util.function.Supplier<VenueWording> load) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.until() > now) {
            return c.words();
        }
        VenueWording w = tenant == null ? null : load.get();
        cache.put(key, new Cached(w == null ? VenueWording.HOTEL : w, now + (w == null ? FAILURE_TTL_MS : TTL_MS)));
        return w == null ? VenueWording.HOTEL : w;
    }

    private VenueWording fetchHotel(String hotelId) {
        try {
            ApiResponse<HotelRef> body = tenant.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            return body == null || body.data() == null ? null : VenueWording.of(body.data().venueType());
        } catch (Exception e) {
            log.warn("Venue type of location {} not read (tenant-service): {}", hotelId, e.getMessage());
            return null;
        }
    }

    private VenueWording fetchBusiness(String businessId) {
        try {
            ApiResponse<BusinessRef> body = tenant.get().uri("/internal/businesses/{id}", businessId).retrieve().body(BUSINESS);
            if (body == null || body.data() == null) {
                return null;
            }
            List<HotelRef> hotels = body.data().hotels();
            return VenueWording.ofBusiness(body.data().venueKind(), hotels == null ? null : hotels.stream().map(HotelRef::venueType).toList());
        } catch (Exception e) {
            log.warn("Venue kind of business {} not read (tenant-service): {}", businessId, e.getMessage());
            return null;
        }
    }

    /** Tests: use these answers instead of tenant-service; null puts hotels back everywhere. */
    public static void useForTests(VenuePlaces places) {
        instance = places;
    }

    /** Tests: a fixed answer for a location or ("b|" + id) a business. */
    void remember(String key, VenueWording words) {
        cache.put(key, new Cached(words, Long.MAX_VALUE));
    }
}
