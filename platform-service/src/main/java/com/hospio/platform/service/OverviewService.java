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
import com.hospio.platform.entity.ActivityEntry;
import com.hospio.platform.entity.HealthScore;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin dashboard and the per-business Usage and Hotels tabs. Figures from other services fail soft. */
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

    @Transactional(readOnly = true)
    public Overview overview() {
        TenantClient.Summary summary = tenant.summary();
        List<TenantClient.Draft> drafts = tenant.onboarding(null);
        long inProgress = drafts.stream().filter(d -> "IN_PROGRESS".equals(d.status())).count();
        long ready = drafts.stream().filter(d -> "READY_FOR_HANDOVER".equals(d.status())).count();
        long good = 0;
        long watch = 0;
        long atRisk = 0;
        for (HealthScore h : healthScores.findAll()) {
            switch (HealthService.bucket(h.getScore())) {
                case "good" -> good++;
                case "watch" -> watch++;
                default -> atRisk++;
            }
        }
        return new Overview(new BusinessCounts(summary.total(), summary.byStage()), summary.hotels(), summary.rooms(),
                fees.billed(YearMonth.now(LAGOS)), fees.outstanding(Set.of("DUE", "PART_PAID")), fees.outstanding(Set.of("OVERDUE")),
                support.openCount(), inProgress, ready, alerts.newestOpen(10), changes.recent(10),
                activity.findByActorNameStartingWithOrderByOccurredAtDesc("H.M.S", PageRequest.of(0, 10)), new HealthBuckets(good, watch, atRisk));
    }

    /** Usage tab: people active, bookings, payments and messages per week for 8 weeks, and what the business has adopted. */
    @Transactional(readOnly = true)
    public Usage usage(String businessId) {
        Instant now = Instant.now();
        LocalDate thisWeek = LocalDate.now(LAGOS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<WeekCount> weeks = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            LocalDate start = thisWeek.minusWeeks(i);
            Instant from = start.atStartOfDay(LAGOS).toInstant();
            Instant to = start.plusWeeks(1).atStartOfDay(LAGOS).toInstant();
            weeks.add(new WeekCount(start,
                    activity.countByBusinessIdAndEventTypeAndOccurredAtBetween(businessId, "BookingCreated", from, to),
                    activity.countByBusinessIdAndEventTypeAndOccurredAtBetween(businessId, "PaymentApproved", from, to),
                    activity.countByBusinessIdAndEventTypeAndOccurredAtBetween(businessId, "MessageSent", from, to)));
        }
        Instant month = now.minus(30, ChronoUnit.DAYS);
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("posUsed", activity.existsByBusinessIdAndEventType(businessId, "PaymentRequested"));
        features.put("whatsappSet", activity.existsByBusinessIdAndEventType(businessId, "MessageSent"));
        features.put("nightAuditClosed", activity.existsByBusinessIdAndEventType(businessId, "DayClosed"));
        features.put("bookingsTaken", activity.existsByBusinessIdAndEventType(businessId, "BookingCreated"));
        features.put("housekeepingUsed", activity.existsByBusinessIdAndEventType(businessId, "RoomCleaned"));
        return new Usage(businessId, activity.countDistinctActorsSince(businessId, now.minus(7, ChronoUnit.DAYS)), activity.countDistinctActorsSince(businessId, month),
                activity.countByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, "BookingCreated", month),
                activity.countByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, "PaymentApproved", month),
                activity.countByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, "MessageSent", month), weeks, features);
    }

    /** Hotels tab: one hotel's figures gathered from property, reservation, folio, payment and hotel-config. */
    public HotelOverview hotelOverview(String businessId, String hotelId) {
        TenantClient.Business b = tenant.business(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        TenantClient.Hotel hotel = b.hotels().stream().filter(h -> hotelId.equals(h.id())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("That hotel is not in this business"));
        Map<String, Object> raw = new LinkedHashMap<>();
        JsonNode property = null;
        JsonNode reservation = null;
        JsonNode folio = null;
        JsonNode payment = null;
        for (String service : hotelOverviews.services()) {
            JsonNode d = hotelOverviews.overview(service, hotelId);
            raw.put(service, d == null ? null : objectMapper.convertValue(d, Map.class));
            switch (service) {
                case "property" -> property = d;
                case "reservation" -> reservation = d;
                case "folio" -> folio = d;
                case "payment" -> payment = d;
                default -> { }
            }
        }
        Integer rooms = property != null && property.has("rooms") ? property.path("rooms").asInt() : hotel.roomCount() > 0 ? hotel.roomCount() : null;
        Integer occupied = reservation != null && reservation.has("inHouse") ? reservation.path("inHouse").asInt() : null;
        BigDecimal occupancy = reservation != null && reservation.has("occupancyPct") ? new BigDecimal(reservation.path("occupancyPct").asText("0")) : null;
        if (occupancy == null && occupied != null && rooms != null && rooms > 0) {
            occupancy = BigDecimal.valueOf(occupied * 100L).divide(BigDecimal.valueOf(rooms), 1, java.math.RoundingMode.HALF_UP);
        }
        JsonNode today = payment == null ? null : payment.path("paymentsToday");
        return new HotelOverview(hotelId, businessId, hotel.name(), hotel.managerUserId(), names.name(hotel.managerUserId()).orElse(null), rooms, occupied, occupancy,
                intOrNull(reservation, "arrivalsToday"), intOrNull(reservation, "departuresToday"), intOrNull(folio, "openFolios"),
                today == null || today.isMissingNode() ? null : today.path("count").asLong(),
                today == null || today.isMissingNode() ? null : new BigDecimal(today.path("gross").asText("0")), raw);
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n != null && n.has(field) && !n.path(field).isNull() ? n.path(field).asInt() : null;
    }
}
