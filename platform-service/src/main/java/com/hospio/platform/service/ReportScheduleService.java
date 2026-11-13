package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.OwnerRequests;
import com.hospio.platform.entity.Report;
import com.hospio.platform.repository.ReportRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Reports sent on a schedule (Owner portal · Reports, docs/owner-portal.md). A schedule is a reports row with status
 * SCHEDULED; each run leaves a READY row for the period it covers and tells the people it is for: an in-app notice that
 * opens the report on the Reports screen (built there from that period's data) and an email with the same link, which
 * on staging is echoed rather than sent. PDF attachments wait for a renderer and an email provider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportScheduleService {

    /** The reports the owner portal can build, by kind. */
    public static final Map<String, String> CATALOGUE = new LinkedHashMap<>();

    static {
        CATALOGUE.put("DAILY_SUMMARY", "Daily summary");
        CATALOGUE.put("OCCUPANCY", "Occupancy");
        CATALOGUE.put("REVENUE", "Revenue by source");
        CATALOGUE.put("PAYMENTS", "Payments & refunds");
        CATALOGUE.put("EXPENSES", "Expenses");
        CATALOGUE.put("GUESTS", "Guest list");
    }

    public static final List<String> EVERY = List.of("DAILY", "WEEKLY", "MONTHLY");
    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final ReportRepository reports;
    private final EventPublisher events;
    private final DeliveryService deliveries;
    private final NameResolver names;
    private final BusinessNames businesses;

    /** The hotel app's address, so an emailed link opens the report (blank in unit tests: links stay relative). */
    @org.springframework.beans.factory.annotation.Value("${hospio.apps.hotel-url:}")
    private String hotelUrl = "";

    public record Run(Report report, List<Delivery> deliveries) {
    }

    /** Owner (any hotel, or all) or manager (their own hotel). The same report for the same scope and person is updated, not doubled. */
    public Report schedule(OwnerRequests.ReportSchedule request) {
        HospioUserDetails user = caller();
        String kind = request.kind().trim().toUpperCase(Locale.ROOT);
        if (!CATALOGUE.containsKey(kind)) {
            throw new BadRequestException("Unknown report " + request.kind() + ". Pick one of: " + String.join(", ", CATALOGUE.values()));
        }
        String every = request.every().trim().toUpperCase(Locale.ROOT);
        if (!EVERY.contains(every)) {
            throw new BadRequestException("How often is Daily, Weekly or Monthly");
        }
        LocalTime at = sendAt(request.sendAt());
        String hotelId = StringUtils.hasText(request.hotelId()) ? request.hotelId() : null;
        if (hotelId != null && !user.getHotelIds().contains(hotelId)) {
            throw new ForbiddenException("That hotel is not one of yours");
        }
        if (hotelId == null && !user.has(Role.OWNER)) {
            hotelId = TenantContext.requireHotel();
        }
        boolean managers = Boolean.TRUE.equals(request.managers());
        if (managers && !user.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner sends reports to the managers");
        }
        String scope = hotelId;
        String sendAt = at.toString();
        String recipients = managers ? "ME,MANAGERS" : "ME";
        return TenantContext.runWith(Tenant.systemFor(user.getBusinessId(), null, user.getUserId(), user.getFullName()), () -> {
            Report r = reports.findByBusinessIdAndStatusAndKindAndRequestedBy(user.getBusinessId(), "SCHEDULED", kind, user.getUserId()).stream()
                    .filter(x -> Objects.equals(x.getHotelId(), scope)).findFirst()
                    .orElseGet(() -> Report.builder().businessId(user.getBusinessId()).hotelId(scope).kind(kind).status("SCHEDULED")
                            .requestedBy(user.getUserId()).build());
            r.setName(CATALOGUE.get(kind));
            r.setSchedule(every);
            r.setSendAt(sendAt);
            r.setRecipients(recipients);
            r.setRequestedByName(user.getFullName());
            r.setNextRunAt(nextRun(every, at, ZonedDateTime.now(LAGOS)));
            return reports.saveAndFlush(r);
        });
    }

    /** Stop a schedule; earlier runs stay listed. */
    public Report stop(String id) {
        HospioUserDetails user = caller();
        Report r = mineToRun(id, user);
        return TenantContext.runWith(Tenant.systemFor(user.getBusinessId(), null, user.getUserId(), user.getFullName()), () -> {
            r.setStatus("STOPPED");
            r.setNextRunAt(null);
            return reports.saveAndFlush(r);
        });
    }

    /** Send the latest full period now (the same as the next run would), and show what went out. */
    public Run runNow(String id) {
        HospioUserDetails user = caller();
        Report schedule = mineToRun(id, user);
        if (!"SCHEDULED".equals(schedule.getStatus())) {
            throw new BadRequestException("This schedule was stopped. Schedule the report again first");
        }
        return TenantContext.runWith(Tenant.systemFor(schedule.getBusinessId(), null, user.getUserId(), user.getFullName()),
                () -> run(schedule, ZonedDateTime.now(LAGOS), false));
    }

    /**
     * The job: every schedule that is due runs once and moves to its next time. Each due schedule is claimed first (next_run_at moved on
     * only if it still holds what was read), so with several instances running the job only the one that claims it sends it.
     */
    public int runDue() {
        List<Report> due = TenantContext.runWith(Tenant.system("report-schedules"),
                () -> reports.findByStatusAndNextRunAtLessThanEqual("SCHEDULED", Instant.now()));
        int ran = 0;
        for (Report schedule : due) {
            try {
                ZonedDateTime now = ZonedDateTime.now(LAGOS);
                Instant next = nextRun(schedule.getSchedule(), sendAt(schedule.getSendAt()), now.plusMinutes(1));
                boolean mine = TenantContext.runWith(Tenant.systemFor(schedule.getBusinessId(), null, "system", "Hospio"),
                        () -> reports.claim(schedule.getId(), schedule.getNextRunAt(), next, Instant.now()) == 1);
                if (!mine) {
                    log.debug("Report schedule {} was taken by another instance", schedule.getId());
                    continue;
                }
                schedule.setNextRunAt(next);
                TenantContext.runWith(Tenant.systemFor(schedule.getBusinessId(), null, "system", "Hospio"),
                        () -> run(schedule, now, true));
                ran++;
            } catch (RuntimeException e) {
                log.warn("Report schedule {} did not run: {}", schedule.getId(), e.getMessage());
            }
        }
        return ran;
    }

    // ---- running ---------------------------------------------------------------------------------

    /**
     * One run. {@code scheduled} is the job's run, already claimed (next_run_at moved on by {@link #runDue}): its notices and email carry
     * ids made from the schedule and period, so a run repeated for the same period is recognised downstream. "Run now" keeps fresh ids.
     */
    private Run run(Report schedule, ZonedDateTime now, boolean scheduled) {
        LocalDate[] period = period(schedule.getSchedule(), now.toLocalDate());
        Report ready = reports.saveAndFlush(Report.builder().businessId(schedule.getBusinessId()).hotelId(schedule.getHotelId())
                .kind(schedule.getKind()).name(schedule.getName()).status("READY").periodFrom(period[0]).periodTo(period[1])
                .scheduleId(schedule.getId()).requestedBy(schedule.getRequestedBy()).requestedByName(schedule.getRequestedByName()).build());
        schedule.setLastRunAt(Instant.now());
        reports.saveAndFlush(schedule);

        String label = periodLabel(period[0], period[1]);
        String title = schedule.getName() + " · " + label + " is ready";
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().eventId(scheduled ? runId(schedule, period, "notice:owner") : null).kind("REPORT")
                .title(title).body("Open it to read or download")
                .link(link(schedule, period, schedule.getHotelId())).businessId(schedule.getBusinessId())
                .userIds(List.of(schedule.getRequestedBy())).build());
        if (schedule.getRecipients() != null && schedule.getRecipients().contains("MANAGERS")) {
            for (String hotelId : hotelsOf(schedule)) {
                events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().eventId(scheduled ? runId(schedule, period, "notice:" + hotelId) : null)
                        .kind("REPORT").title(title)
                        .body("Sent by " + schedule.getRequestedByName() + " · your hotel only").link(link(schedule, period, hotelId))
                        .businessId(schedule.getBusinessId()).hotelId(hotelId).roles(List.of(Role.MANAGER.name())).build());
            }
        }
        List<Delivery> out = new ArrayList<>();
        names.contact(schedule.getRequestedBy()).filter(c -> StringUtils.hasText(c.email())).ifPresent(c -> out.add(Delivery.email(c.email(), title,
                "Hello " + c.fullName() + ",\n\nYour " + schedule.getName().toLowerCase(Locale.ROOT) + " for " + label + " is ready.\n\n"
                        + "Open it in Hospio360: Reports → " + schedule.getName() + "\n\n" + absolute(link(schedule, period, schedule.getHotelId())) + "\n",
                Delivery.Kind.DAILY_REPORT, ready.getId())));
        if (scheduled) {
            // Published with a stable event id (schedule + period), so messaging sends the email once even if the run is repeated.
            for (Delivery d : out) {
                events.publish(KafkaTopics.DELIVERY_REQUESTED, DeliveryRequestedEvent.builder().eventId(runId(schedule, period, "email:" + d.to()))
                        .businessId(schedule.getBusinessId()).channel(d.channel().name()).to(d.to()).subject(d.subject()).text(d.text())
                        .kind(d.kind().name()).ref(d.ref()).build());
            }
            return new Run(ready, out);
        }
        return new Run(ready, deliveries.send(out));
    }

    /** A 36-character id fixed by the schedule, the period it covers and what is sent (scheduleId:periodFrom:what). */
    static String runId(Report schedule, LocalDate[] period, String what) {
        return java.util.UUID.nameUUIDFromBytes((schedule.getId() + ":" + period[0] + ":" + what).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    public record Emailed(String to, List<Delivery> deliveries) {
    }

    /**
     * "Email me" on the Reports screen: the caller gets an email with a link that opens the same report (kind, period, hotel)
     * in the app. A manager's report is for one of their hotels; no attachment (docs/email.md), the link builds it from live data.
     */
    public Emailed emailMe(OwnerRequests.ReportEmail request) {
        HospioUserDetails user = caller();
        String kind = request.kind() == null ? "" : request.kind().trim().toUpperCase(Locale.ROOT);
        if (!CATALOGUE.containsKey(kind)) {
            throw new BadRequestException("Unknown report " + request.kind() + ". Pick one of: " + String.join(", ", CATALOGUE.values()));
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(request.from());
            to = LocalDate.parse(request.to());
        } catch (RuntimeException e) {
            throw new BadRequestException("The period is two dates like 2026-11-01");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("The start date is after the end date");
        }
        String hotelId = StringUtils.hasText(request.hotelId()) ? request.hotelId() : null;
        if (hotelId != null && !user.getHotelIds().contains(hotelId)) {
            throw new ForbiddenException("That hotel is not one of yours");
        }
        if (hotelId == null && !user.has(Role.OWNER)) {
            hotelId = TenantContext.requireHotel();
        }
        IdentityContact me = names.contact(user.getUserId()).map(c -> new IdentityContact(c.fullName(), c.email()))
                .filter(c -> StringUtils.hasText(c.email()))
                .orElseThrow(() -> new BadRequestException("Your account has no email address to send the report to"));
        String name = CATALOGUE.get(kind);
        String label = periodLabel(from, to);
        String link = "/reports?report=" + kind + "&from=" + from + "&to=" + to + (hotelId == null ? "" : "&hotel=" + hotelId);
        Delivery d = Delivery.email(me.email(), name + " · " + label,
                "Hello " + me.fullName() + ",\n\nHere is the " + name.toLowerCase(Locale.ROOT) + " for " + label + ". It opens in Hospio360, built from "
                        + "live data, where you can read it or download it as CSV.\n\n" + absolute(link) + "\n",
                Delivery.Kind.REPORT, kind + ":" + from + ":" + to);
        return new Emailed(me.email(), deliveries.send(List.of(d)));
    }

    private record IdentityContact(String fullName, String email) {
    }

    String absolute(String path) {
        if (!StringUtils.hasText(hotelUrl)) {
            return path;
        }
        return hotelUrl.replaceAll("/+$", "") + "/dashboard" + path;
    }

    private List<String> hotelsOf(Report schedule) {
        if (schedule.getHotelId() != null) {
            return List.of(schedule.getHotelId());
        }
        return businesses.business(schedule.getBusinessId()).map(b -> b.hotels().stream().map(TenantClient.Hotel::id).toList()).orElse(List.of());
    }

    static String link(Report schedule, LocalDate[] period, String hotelId) {
        return "/reports?report=" + schedule.getKind() + "&from=" + period[0] + "&to=" + period[1] + (hotelId == null ? "" : "&hotel=" + hotelId);
    }

    /** The period a run covers: yesterday, last Monday to Sunday, or last month. */
    static LocalDate[] period(String every, LocalDate today) {
        return switch (every) {
            case "WEEKLY" -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                yield new LocalDate[]{monday, monday.plusDays(6)};
            }
            case "MONTHLY" -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new LocalDate[]{first, first.with(TemporalAdjusters.lastDayOfMonth())};
            }
            default -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
        };
    }

    /** The next time a schedule goes out after {@code now}: daily, Mondays, or the 1st, at {@code at} Lagos time. */
    static Instant nextRun(String every, LocalTime at, ZonedDateTime now) {
        ZonedDateTime candidate = now.toLocalDate().atTime(at).atZone(LAGOS);
        switch (every) {
            case "WEEKLY" -> {
                candidate = candidate.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusWeeks(1);
                }
            }
            case "MONTHLY" -> {
                candidate = candidate.withDayOfMonth(1);
                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusMonths(1);
                }
            }
            default -> {
                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusDays(1);
                }
            }
        }
        return candidate.toInstant();
    }

    static String periodLabel(LocalDate from, LocalDate to) {
        return from.equals(to) ? DAY.format(from) : DAY.format(from) + " to " + DAY.format(to);
    }

    static LocalTime sendAt(String value) {
        try {
            return LocalTime.parse(value == null ? "" : value.trim());
        } catch (RuntimeException e) {
            throw new BadRequestException("Send time is a time of day like 07:00");
        }
    }

    private static HospioUserDetails caller() {
        HospioUserDetails user = CurrentUser.require();
        if (user.isPlatform() || user.getBusinessId() == null || !user.isManagerOrOwner() || user.isReadOnly()) {
            throw new ForbiddenException("Only the owner or a manager can schedule reports");
        }
        return user;
    }

    /** A schedule the caller made, or (owner) any schedule of the business. */
    private Report mineToRun(String id, HospioUserDetails user) {
        Report r = TenantContext.runWith(Tenant.systemFor(user.getBusinessId(), null, user.getUserId(), user.getFullName()),
                () -> reports.findById(id)).filter(x -> user.getBusinessId().equals(x.getBusinessId()) && x.getSendAt() != null)
                .orElseThrow(() -> new ResourceNotFoundException("That schedule was not found"));
        if (!user.has(Role.OWNER) && !user.getUserId().equals(r.getRequestedBy())) {
            throw new ForbiddenException("Only the person who scheduled it, or the owner, can change it");
        }
        return r;
    }
}
