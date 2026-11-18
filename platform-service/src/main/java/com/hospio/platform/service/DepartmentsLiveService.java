package com.hospio.platform.service;

import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.kafka.events.OrderCreatedEvent;
import com.hospio.common.kafka.events.OrderReadyEvent;
import com.hospio.common.kafka.events.OrderSettledEvent;
import com.hospio.common.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Manager · Departments (live) and Owner · Operations: what each department did in the last hour,
 * kept in Redis so the board costs the database nothing. Each event bumps its department's counter.
 */
@Service
@RequiredArgsConstructor
public class DepartmentsLiveService {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration OUTLET_KIND_TTL = Duration.ofDays(90);
    private static final Map<String, String> TOPIC_TO_DEPARTMENT = Map.ofEntries(
            Map.entry("booking", "FRONT_OFFICE"), Map.entry("guest", "FRONT_OFFICE"), Map.entry("folio", "FRONT_OFFICE"), Map.entry("charge", "FRONT_OFFICE"),
            Map.entry("room-cleaned", "HOUSEKEEPING"), Map.entry("problem", "HOUSEKEEPING"), Map.entry("linen", "LAUNDRY"),
            Map.entry("repair", "MAINTENANCE"), Map.entry("parts", "MAINTENANCE"), Map.entry("room-out", "MAINTENANCE"), Map.entry("room-back", "MAINTENANCE"),
            Map.entry("order", "KITCHEN"), Map.entry("item-sold", "BAR"),
            Map.entry("stock", "STORE"), Map.entry("delivery", "STORE"),
            Map.entry("payment", "FINANCE"), Map.entry("expense", "FINANCE"), Map.entry("day-closed", "FINANCE"), Map.entry("cash", "FINANCE"), Map.entry("refund", "FINANCE"), Map.entry("pos", "FINANCE"));

    private final StringRedisTemplate redis;

    public void bump(String topic, HospioEvent event) {
        if (event.getHotelId() == null) {
            return;
        }
        String dept = department(topic);
        if (dept == null) {
            return;
        }
        if ("KITCHEN".equals(dept) && "BAR".equals(outletKind(event))) {
            // A bar's orders are the bar's work, not the kitchen's (the lounge's kitchen card showed "New BAR order").
            dept = "BAR";
        }
        String key = "hospio:live:" + event.getHotelId() + ":" + dept;
        long now = Instant.now().toEpochMilli();
        redis.opsForZSet().add(key, event.getEventId() == null ? String.valueOf(now) : event.getEventId(), now);
        redis.opsForZSet().removeRangeByScore(key, 0, now - WINDOW.toMillis());
        redis.expire(key, WINDOW.multipliedBy(2));
        redis.opsForValue().set(key + ":last", (event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt()) + "|" + event.summary(), WINDOW.multipliedBy(24));
    }

    /**
     * The kind of outlet an order event belongs to: created and ready events carry it and it is remembered per outlet,
     * so a settled event (which names only the outlet) finds it too. Null when not known.
     */
    String outletKind(HospioEvent event) {
        String outletId = null;
        String kind = null;
        if (event instanceof OrderCreatedEvent e) {
            outletId = e.getOutletId();
            kind = e.getOutletKind();
        } else if (event instanceof OrderReadyEvent e) {
            outletId = e.getOutletId();
            kind = e.getOutletKind();
        } else if (event instanceof OrderSettledEvent e) {
            outletId = e.getOutletId();
        }
        if (outletId == null) {
            return kind;
        }
        String key = "hospio:outlet-kind:" + outletId;
        if (kind != null) {
            redis.opsForValue().set(key, kind, OUTLET_KIND_TTL);
            return kind;
        }
        return redis.opsForValue().get(key);
    }

    public Map<String, Map<String, Object>> board() {
        String hotelId = TenantContext.requireHotel();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        long cutoff = Instant.now().minus(WINDOW).toEpochMilli();
        for (String dept : List.of("FRONT_OFFICE", "HOUSEKEEPING", "LAUNDRY", "MAINTENANCE", "KITCHEN", "BAR", "STORE", "FINANCE")) {
            String key = "hospio:live:" + hotelId + ":" + dept;
            Long count = redis.opsForZSet().count(key, cutoff, Double.MAX_VALUE);
            String last = redis.opsForValue().get(key + ":last");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actionsLastHour", count == null ? 0 : count);
            row.put("lastAt", last == null ? null : last.substring(0, last.indexOf('|')));
            row.put("lastAction", last == null ? null : last.substring(last.indexOf('|') + 1));
            out.put(dept, row);
        }
        return out;
    }

    static String department(String topic) {
        String t = topic.startsWith("hospio-") ? topic.substring(7) : topic;
        for (Map.Entry<String, String> e : TOPIC_TO_DEPARTMENT.entrySet()) {
            if (t.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
