package com.hospio.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.ActivityEntry;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.paging.Paging;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every action → activity log. Manager · Activity log, Owner · Operations 4-5, Admin · H3 and Changes by Hospio360. */
@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityEntryRepository entries;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(String topic, HospioEvent event) {
        if (event.getEventId() != null && entries.findByEventId(event.getEventId()).isPresent()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            payload = null;
        }
        String summary = event.summary();
        entries.save(ActivityEntry.builder().businessId(event.getBusinessId()).hotelId(event.getHotelId()).eventId(event.getEventId())
                .eventType(event.type()).topic(topic).actorId(event.getActorId()).actorName(event.getActorName())
                .summary(summary == null ? event.type() : (summary.length() > 500 ? summary.substring(0, 500) : summary))
                .payload(payload).occurredAt(event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt()).build());
    }

    /** The largest page the activity log answers (exports read it 200 at a time). */
    public static final int MAX_SIZE = 200;

    /** Department → the topic prefixes (without "hospio-") it covers, as the web's Departments board names them. */
    /** The platform as a department: its new name and the one older log rows were written under. */
    static final java.util.Set<String> PLATFORM_NAMES = java.util.Set.of("Hospio360", "H.M.S");


    public static final Map<String, List<String>> DEPARTMENT_TOPICS = new LinkedHashMap<>();

    static {
        DEPARTMENT_TOPICS.put("Front office", List.of("booking", "guest", "folio", "charge"));
        DEPARTMENT_TOPICS.put("Housekeeping", List.of("room-cleaned", "problem"));
        DEPARTMENT_TOPICS.put("Laundry", List.of("linen"));
        DEPARTMENT_TOPICS.put("Maintenance", List.of("repair", "parts", "room-out", "room-back"));
        DEPARTMENT_TOPICS.put("Kitchen", List.of("order"));
        DEPARTMENT_TOPICS.put("Bar", List.of("item-sold"));
        DEPARTMENT_TOPICS.put("Store", List.of("stock", "delivery-received"));
        DEPARTMENT_TOPICS.put("Finance", List.of("payment", "expense", "day-closed", "cash", "refund", "pos", "till"));
        DEPARTMENT_TOPICS.put("Management", List.of("user", "role", "access", "approval", "shared-device", "setting", "notice"));
    }

    /** What the log screen filters on: a search, a department, Lagos days (both inclusive) and Hospio360 changes only. */
    public record Filter(String businessId, String q, String department, LocalDate from, LocalDate to, boolean platformOnly) {
    }

    /**
     * A manager sees their hotel; the owner the whole business (or one hotel with X-Hotel-Id); Hospio360 a business they name.
     * Filtered, counted and paged in the database, newest first. {@code size} is 1 to 200 (the export reads 200 at a time).
     */
    @Transactional(readOnly = true)
    public Page<ActivityEntry> list(Filter f, Integer page, Integer size) {
        HospioUserDetails user = CurrentUser.require();
        int p = page == null ? 0 : page;
        int s = size == null ? 50 : size;
        if (p < 0) {
            throw new BadRequestException("page counts from 0");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new BadRequestException("size is 1 to " + MAX_SIZE);
        }
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new BadRequestException("The start date is after the end date");
        }
        String business = user.isPlatform() ? f.businessId() : TenantContext.requireBusiness();
        String hotel = user.isPlatform() ? null : TenantContext.current().map(t -> t.hotelId()).orElse(null);
        List<String> topics = null;
        if (f.department() != null && !f.department().isBlank()) {
            String d = f.department().trim();
            if (!PLATFORM_NAMES.contains(d)) {
                topics = DEPARTMENT_TOPICS.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(d)).findFirst().map(Map.Entry::getValue)
                        .orElseThrow(() -> new BadRequestException("Unknown department " + d + ". Pick one of: "
                                + String.join(", ", DEPARTMENT_TOPICS.keySet()) + ", Hospio360"));
            }
        }
        boolean platformOnly = f.platformOnly() || (f.department() != null && PLATFORM_NAMES.contains(f.department().trim()));
        Specification<ActivityEntry> spec = spec(business, hotel, f.q(), topics, f.from(), f.to(), platformOnly);
        return entries.findAll(spec, PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    static Specification<ActivityEntry> spec(String business, String hotel, String q, List<String> topics, LocalDate from, LocalDate to,
                                             boolean platformOnly) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (business != null) {
                where.add(cb.equal(root.get("businessId"), business));
            }
            if (hotel != null) {
                where.add(cb.equal(root.get("hotelId"), hotel));
            }
            if (platformOnly) {
                where.add(cb.or(cb.like(root.get("actorName"), "Hospio360%"), cb.like(root.get("actorName"), "H.M.S%")));
            }
            if (topics != null) {
                List<Predicate> any = new ArrayList<>();
                for (String t : topics) {
                    any.add(cb.like(root.get("topic"), KafkaTopics.PREFIX + t + "%"));
                }
                where.add(cb.or(any.toArray(new Predicate[0])));
                where.add(cb.or(cb.isNull(root.get("actorName")), cb.and(cb.notLike(root.get("actorName"), "Hospio360%"), cb.notLike(root.get("actorName"), "H.M.S%"))));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from.atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("occurredAt"), to.plusDays(1).atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (q != null && !q.isBlank()) {
                String like = Paging.like(q);
                where.add(cb.or(cb.like(cb.lower(root.get("summary")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("actorName"), "")), like, '\\'),
                        cb.like(cb.lower(root.get("eventType")), like, '\\')));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public ActivityEntry get(String id) {
        return entries.findById(id).orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Entry not found"));
    }
}
