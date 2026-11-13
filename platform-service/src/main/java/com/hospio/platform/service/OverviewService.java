package com.hospio.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.platform.client.HotelOverviewClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.AdminResponses.BusinessCounts;
import com.hospio.platform.dto.AdminResponses.HealthBuckets;
import com.hospio.platform.dto.AdminResponses.HotelOverview;
import com.hospio.platform.dto.AdminResponses.Overview;
import com.hospio.platform.dto.AdminResponses.Usage;
import com.hospio.platform.dto.AdminResponses.WeekCount;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.HealthScoreRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin dashboard and the per-business Usage and Hotels tabs. Figures from other services fail soft. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final TenantClient tenant;
    private final FeeService fees;
    private final SupportService support;
    private final AlertService alerts;
    private final ChangeService changes;
    private final HealthScoreRepository healthScores;
    private final ActivityEntryRepository activity;
    private final HotelOverviewClient hotelOverviews;
    private final NameResolver names;
    private final ObjectMapper objectMapper;

    /** How long the tenant-service figures on the dashboard (business counts, onboarding) are reused. */
    static final Duration TENANT_FIGURES_TTL = Duration.ofSeconds(60);
    /** How far back the dashboard's "recent Hospio360 activity" looks (an index range instead of the whole log). */
    static final Duration RECENT_ACTIVITY = Duration.ofDays(30);
    /** Usage tab: "has the business ever …" looks back this far (the log keeps a year by default; older rows are deleted). */
    static final Duration FEATURE_WINDOW = Duration.ofDays(366);
    /** The hotel overview's budget: the most the per-service figures may take together; slower services show nothing, as when they fail. */
    static final Duration HOTEL_OVERVIEW_DEADLINE = Duration.ofSeconds(3);

    record TenantFigures(TenantClient.Summary summary, long onboardingInProgress, long readyForHandover, Instant until) {
    }

    private final AtomicReference<TenantFigures> tenantFigures = new AtomicReference<>();

    /**
     * The admin dashboard. No transaction around it: tenant-service is asked first (and its answer reused for a minute), then each
     * figure is one short query (counts and sums in the database, not rows loaded to add up in Java).
     */
    public Overview overview() {
        TenantFigures t = tenantFigures();
        long good = 0;
        long watch = 0;
        long atRisk = 0;
        List<Object[]> buckets = healthScores.buckets(HealthService.GOOD, HealthService.WATCH);
        if (!buckets.isEmpty() && buckets.get(0) != null) {
            Object[] row = buckets.get(0);
            good = asLong(row[0]);
            watch = asLong(row[1]);
            atRisk = asLong(row[2]);
        }
        return new Overview(new BusinessCounts(t.summary().total(), t.summary().byStage()), t.summary().hotels(), t.summary().rooms(),
                fees.billed(YearMonth.now(LAGOS)), fees.outstanding(Set.of("DUE", "PART_PAID")), fees.outstanding(Set.of("OVERDUE")),
                support.openCount(), t.onboardingInProgress(), t.readyForHandover(), alerts.newestOpen(10), changes.recent(10),
                activity.findByActorNameStartingWithAndOccurredAtAfterOrderByOccurredAtDesc("Hospio360", Instant.now().minus(RECENT_ACTIVITY),
                        PageRequest.of(0, 10)), new HealthBuckets(good, watch, atRisk));
    }

    private TenantFigures tenantFigures() {
        TenantFigures cached = tenantFigures.get();
        Instant now = Instant.now();
        if (cached != null && cached.until().isAfter(now)) {
            return cached;
        }
        TenantClient.Summary summary = tenant.summary();
        long inProgress;
        long ready;
        Map<String, Long> counts = tenant.onboardingCounts();
        if (counts != null) {
            inProgress = counts.getOrDefault("IN_PROGRESS", 0L);
            ready = counts.getOrDefault("READY_FOR_HANDOVER", 0L);
        } else {
            // The counts weren't there: count the drafts as before.
            List<TenantClient.Draft> drafts = tenant.onboarding(null);
            inProgress = drafts.stream().filter(d -> "IN_PROGRESS".equals(d.status())).count();
            ready = drafts.stream().filter(d -> "READY_FOR_HANDOVER".equals(d.status())).count();
        }
        TenantFigures fresh = new TenantFigures(summary, inProgress, ready, now.plus(TENANT_FIGURES_TTL));
        tenantFigures.set(fresh);
        return fresh;
    }

    static final List<String> WEEKLY_TYPES = List.of("BookingCreated", "PaymentApproved", "MessageSent");
    static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        FEATURES.put("posUsed", "PaymentRequested");
        FEATURES.put("whatsappSet", "MessageSent");
        FEATURES.put("nightAuditClosed", "DayClosed");
        FEATURES.put("bookingsTaken", "BookingCreated");
        FEATURES.put("housekeepingUsed", "RoomCleaned");
    }

    /**
     * Usage tab: people active, bookings, payments and messages per week for 8 weeks, and what the business has adopted. One grouped query
     * for the weeks, one for the 30-day counts, the two active-people counts and one bounded "any?" per feature (index lookups).
     */
    @Transactional(readOnly = true)
    public Usage usage(String businessId) {
        Instant now = Instant.now();
        LocalDate thisWeek = LocalDate.now(LAGOS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = thisWeek.minusWeeks(7);
        Instant from = first.atStartOfDay(LAGOS).toInstant();
        Instant to = thisWeek.plusWeeks(1).atStartOfDay(LAGOS).toInstant();
        long[][] perWeek = weekly(activity.weeklyCounts(businessId, WEEKLY_TYPES, from, to), 8);
        List<WeekCount> weeks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            weeks.add(new WeekCount(first.plusWeeks(i), perWeek[i][0], perWeek[i][1], perWeek[i][2]));
        }
        Instant month = now.minus(30, ChronoUnit.DAYS);
        Map<String, Long> last30 = new HashMap<>();
        for (Object[] row : activity.countsByType(businessId, WEEKLY_TYPES, month)) {
            last30.put((String) row[0], asLong(row[1]));
        }
        Instant ever = now.minus(FEATURE_WINDOW);
        Map<String, Boolean> features = new LinkedHashMap<>();
        FEATURES.forEach((feature, type) -> features.put(feature, activity.existsByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, type, ever)));
        return new Usage(businessId, activity.countDistinctActorsSince(businessId, now.minus(7, ChronoUnit.DAYS)), activity.countDistinctActorsSince(businessId, month),
                last30.getOrDefault("BookingCreated", 0L), last30.getOrDefault("PaymentApproved", 0L), last30.getOrDefault("MessageSent", 0L), weeks, features);
    }

    /** Rows of (week index, event type, count) → [week][bookings, payments, messages]; weeks outside 0..n-1 are ignored. */
    static long[][] weekly(List<Object[]> rows, int n) {
        long[][] out = new long[n][3];
        for (Object[] row : rows) {
            int w = ((Number) row[0]).intValue();
            int k = WEEKLY_TYPES.indexOf((String) row[1]);
            if (w >= 0 && w < n && k >= 0) {
                out[w][k] += asLong(row[2]);
            }
        }
        return out;
    }

    static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Hotels tab: one hotel's figures gathered from property, reservation, folio, payment and hotel-config, asked all at once (virtual
     * threads) with {@link #HOTEL_OVERVIEW_DEADLINE} for the lot. A service that fails or hasn't answered by then shows no figures, as before.
     */
    public HotelOverview hotelOverview(String businessId, String hotelId) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<String, Future<JsonNode>> asked = new LinkedHashMap<>();
            for (String service : hotelOverviews.services()) {
                asked.put(service, pool.submit(() -> hotelOverviews.overview(service, hotelId)));
            }
            TenantClient.Business b = tenant.business(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
            TenantClient.Hotel hotel = b.hotels().stream().filter(h -> hotelId.equals(h.id())).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("That hotel is not in this business"));
            Future<String> managerName = pool.submit(() -> names.name(hotel.managerUserId()).orElse(null));
            long deadline = System.nanoTime() + HOTEL_OVERVIEW_DEADLINE.toNanos();

            Map<String, Object> raw = new LinkedHashMap<>();
            JsonNode property = null;
            JsonNode reservation = null;
            JsonNode folio = null;
            JsonNode payment = null;
            for (Map.Entry<String, Future<JsonNode>> e : asked.entrySet()) {
                JsonNode d = within(e.getValue(), deadline, e.getKey());
                raw.put(e.getKey(), d == null ? null : objectMapper.convertValue(d, Map.class));
                switch (e.getKey()) {
                    case "property" -> property = d;
                    case "reservation" -> reservation = d;
                    case "folio" -> folio = d;
                    case "payment" -> payment = d;
                    default -> { }
                }
            }
            String manager = within(managerName, deadline, "identity");
            Integer rooms = property != null && property.has("rooms") ? property.path("rooms").asInt() : hotel.roomCount() > 0 ? hotel.roomCount() : null;
            Integer occupied = reservation != null && reservation.has("inHouse") ? reservation.path("inHouse").asInt() : null;
            BigDecimal occupancy = reservation != null && reservation.has("occupancyPct") ? new BigDecimal(reservation.path("occupancyPct").asText("0")) : null;
            if (occupancy == null && occupied != null && rooms != null && rooms > 0) {
                occupancy = BigDecimal.valueOf(occupied * 100L).divide(BigDecimal.valueOf(rooms), 1, java.math.RoundingMode.HALF_UP);
            }
            JsonNode today = payment == null ? null : payment.path("paymentsToday");
            return new HotelOverview(hotelId, businessId, hotel.name(), hotel.managerUserId(), manager, rooms, occupied, occupancy,
                    intOrNull(reservation, "arrivalsToday"), intOrNull(reservation, "departuresToday"), intOrNull(folio, "openFolios"),
                    today == null || today.isMissingNode() ? null : today.path("count").asLong(),
                    today == null || today.isMissingNode() ? null : new BigDecimal(today.path("gross").asText("0")), raw);
        } finally {
            // Whatever is still running is not waited for.
            pool.shutdownNow();
        }
    }

    /** The answer if it arrives before the deadline; null when it failed or is late. */
    static <T> T within(Future<T> f, long deadlineNanos, String what) {
        try {
            return f.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.warn("Hotel overview: {} didn't answer within {}; shown without it", what, HOTEL_OVERVIEW_DEADLINE);
            f.cancel(true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Hotel overview: {} failed: {}", what, e.getMessage());
            return null;
        }
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n != null && n.has(field) && !n.path(field).isNull() ? n.path(field).asInt() : null;
    }
}
