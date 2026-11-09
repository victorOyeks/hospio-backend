package com.hospio.finance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.DayClosedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.finance.client.HotelSettings;
import com.hospio.finance.client.PeerServices;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.MoneyToday;
import com.hospio.finance.dto.NightAuditView;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.NightAuditRepository;
import com.hospio.finance.repository.TillRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Closing the day: a computed checklist from reservations, folios and tills, then the report and the owner's summary. */
@Service
@RequiredArgsConstructor
public class NightAuditService {

    private final NightAuditRepository audits;
    private final TillRepository tills;
    private final ExpenseRepository expenses;
    private final MoneyService money;
    private final PeerServices peers;
    private final HotelSettings settings;
    private final Notifier notifier;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    private record Computed(PeerServices.Day day, List<Till> openTills, List<PeerServices.Owing> owing, List<NightAuditChecklist.Item> checklist, int rooms) {
    }

    /** The audit if one exists, with the live checklist while the day is open. Never creates a row. */
    @Transactional(readOnly = true)
    public NightAuditView view(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(MoneyService.LAGOS) : date;
        NightAudit a = audits.findByHotelIdAndBusinessDate(hotelId, d).orElse(null);
        if (a != null && a.getStatus() == NightAudit.Status.CLOSED) {
            return closedView(a);
        }
        Computed c = compute(hotelId, d);
        return new NightAuditView(a == null ? null : a.getId(), d, a == null ? NightAudit.Status.OPEN.name() : a.getStatus().name(), null, null, null, null, null,
                a == null ? null : a.getNote(), null, a == null ? null : a.getChecklist(), c.checklist(), c.day().arrivals(), c.day().departures(),
                c.day().inHouse(), c.day().noShows(), c.day().roomsOccupied(), c.rooms(), null);
    }

    @Transactional(readOnly = true)
    public List<NightAudit> past() {
        return audits.findByHotelIdOrderByBusinessDateDesc(TenantContext.requireHotel());
    }

    @Transactional
    public NightAudit saveChecklist(LocalDate date, FinanceRequests.ChecklistRequest request) {
        NightAudit a = row(date);
        a.setChecklist(request.checklist());
        return audits.save(a);
    }

    @Transactional
    public NightAuditView close(LocalDate date, FinanceRequests.CloseDayRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (user.isReadOnly() || (!user.isManagerOrOwner() && !user.isPlatform() && !user.has(Role.FINANCE, AccessLevel.FULL))) {
            throw new ForbiddenException("Closing the day needs Finance Full or a manager");
        }
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(MoneyService.LAGOS) : date;
        NightAudit a = row(d);
        if (a.getStatus() == NightAudit.Status.CLOSED) {
            throw new BadRequestException("This day is already closed");
        }
        Computed c = compute(hotelId, d);
        if (!c.openTills().isEmpty()) {
            throw new BadRequestException(c.openTills().size() + " till(s) are still open. Count and close them first");
        }
        boolean markNoShows = request != null && Boolean.TRUE.equals(request.markNoShows());
        int marked = 0;
        if (c.day().arrivalsPending() > 0) {
            if (markNoShows) {
                marked = peers.markNoShows(hotelId, d);
            } else if (settings.arrivalsResolvedFirst(hotelId)) {
                throw new BadRequestException(c.day().arrivalsPending() + " expected arrival(s) haven't checked in. Check them in, or close the day marking them as no-shows");
            }
        }

        MoneyToday today = money.today(d);
        BigDecimal expensesTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Expense e : expenses.findByHotelIdAndExpenseDateBetween(hotelId, d, d)) {
            if (e.getStatus() != Expense.Status.DECLINED) {
                expensesTotal = expensesTotal.add(e.getAmount());
                byCategory.merge(e.getCategory(), e.getAmount(), BigDecimal::add);
            }
        }
        List<Till> closedToday = tills.findByHotelIdAndClosedAtBetween(hotelId, d.atStartOfDay(MoneyService.LAGOS).toInstant(), d.plusDays(1).atStartOfDay(MoneyService.LAGOS).toInstant());
        BigDecimal expectedCash = closedToday.isEmpty() ? today.byMethod().getOrDefault("CASH", BigDecimal.ZERO)
                : closedToday.stream().map(Till::getExpectedCash).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal counted = request != null && request.cashCounted() != null ? Money.of(request.cashCounted())
                : closedToday.isEmpty() ? null : closedToday.stream().map(t -> t.getCountedCash() == null ? BigDecimal.ZERO : t.getCountedCash()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal variance = counted == null ? null : Money.subtract(counted, expectedCash);
        int occupied = request != null && request.roomsOccupied() != null ? request.roomsOccupied() : c.day().roomsOccupied();
        int noShows = c.day().noShows() + marked;

        ObjectNode report = mapper.createObjectNode();
        report.put("date", d.toString());
        report.put("revenue", today.revenue());
        report.set("payments", mapper.valueToTree(today.byMethod()));
        report.set("revenueByRole", mapper.valueToTree(today.byRole()));
        report.put("paymentsCount", today.paymentsCount());
        report.put("expenses", Money.of(expensesTotal));
        report.set("expensesByCategory", mapper.valueToTree(byCategory));
        report.put("net", Money.of(today.revenue().subtract(expensesTotal)));
        ObjectNode cash = report.putObject("cash");
        cash.put("expected", Money.of(expectedCash));
        cash.put("counted", counted);
        cash.put("variance", variance);
        cash.put("unattached", today.unattachedCash());
        ObjectNode occ = report.putObject("occupancy");
        occ.put("roomsOccupied", occupied);
        occ.put("rooms", c.rooms());
        occ.set("pct", c.rooms() > 0 ? mapper.valueToTree(MoneyService.pct(occupied, c.rooms())) : mapper.nullNode());
        ObjectNode guests = report.putObject("guests");
        guests.put("arrivals", c.day().arrivals());
        guests.put("departures", c.day().departures());
        guests.put("inHouse", c.day().inHouse());
        guests.put("noShows", noShows);
        guests.put("markedNoShow", marked);
        ArrayNode tillRows = report.putArray("tills");
        for (Till t : closedToday) {
            ObjectNode row = tillRows.addObject();
            row.put("role", t.getRole());
            row.put("expected", t.getExpectedCash());
            row.put("counted", t.getCountedCash());
            row.put("variance", t.getVariance());
        }
        report.put("balancesOwing", c.owing().size());
        report.put("closedBy", user.getFullName());

        a.setRevenue(today.revenue());
        a.setCashCounted(counted);
        a.setVariance(variance);
        a.setRoomsOccupied(occupied);
        a.setNote(request == null ? null : request.note());
        a.setReport(write(report));
        a.setStatus(NightAudit.Status.CLOSED);
        a.setClosedBy(user.getUserId());
        a.setClosedAt(Instant.now());
        audits.save(a);
        events.publish(KafkaTopics.DAY_CLOSED, DayClosedEvent.builder().auditId(a.getId()).businessDate(d).revenue(a.getRevenue())
                .cashCounted(a.getCashCounted()).variance(a.getVariance()).closedBy(user.getFullName()).build());

        List<Delivery> sent = tellOwner(a, d, today, expensesTotal, occupied, c.rooms(), variance, user.getFullName(), hotelId);
        NightAuditView view = closedView(a);
        return new NightAuditView(view.id(), view.businessDate(), view.status(), view.revenue(), view.cashCounted(), view.variance(), view.closedBy(), view.closedAt(),
                view.note(), view.report(), view.savedChecklist(), view.checklist(), view.arrivals(), view.departures(), view.inHouse(), view.noShows(),
                view.roomsOccupied(), view.rooms(), sent);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private NightAudit row(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(MoneyService.LAGOS) : date;
        return audits.findByHotelIdAndBusinessDate(hotelId, d).orElseGet(() -> audits.save(NightAudit.builder().businessDate(d).build()));
    }

    private Computed compute(String hotelId, LocalDate d) {
        PeerServices.Day day = peers.day(hotelId, d);
        List<Till> open = tills.findByHotelIdAndStatusOrderByOpenedAt(hotelId, Till.Status.OPEN);
        Set<String> departing = new HashSet<>();
        day.departingToday().forEach(s -> departing.add(s.bookingId()));
        day.pendingDepartures().forEach(s -> departing.add(s.bookingId()));
        List<PeerServices.Owing> owing = peers.openBalances(hotelId).stream().filter(o -> o.bookingId() != null && departing.contains(o.bookingId())).toList();
        BigDecimal owed = owing.stream().map(PeerServices.Owing::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> roles = open.stream().map(t -> t.getRole().toLowerCase().replace('_', ' ')).toList();
        int rooms = Math.max(peers.roomCount(hotelId), 0);
        return new Computed(day, open, owing, NightAuditChecklist.build(day.arrivalsPending(), day.departuresPending(), roles, owing.size(), Money.of(owed)), rooms);
    }

    private NightAuditView closedView(NightAudit a) {
        JsonNode r = read(a.getReport());
        JsonNode guests = r.path("guests");
        JsonNode occ = r.path("occupancy");
        List<NightAuditChecklist.Item> done = new ArrayList<>(NightAuditChecklist.build(0, 0, List.of(), 0, BigDecimal.ZERO));
        return new NightAuditView(a.getId(), a.getBusinessDate(), a.getStatus().name(), a.getRevenue(), a.getCashCounted(), a.getVariance(), a.getClosedBy(), a.getClosedAt(),
                a.getNote(), a.getReport(), a.getChecklist(), done, guests.path("arrivals").asInt(), guests.path("departures").asInt(), guests.path("inHouse").asInt(),
                guests.path("noShows").asInt(), a.getRoomsOccupied() == null ? occ.path("roomsOccupied").asInt() : a.getRoomsOccupied(), occ.path("rooms").asInt(), null);
    }

    private List<Delivery> tellOwner(NightAudit a, LocalDate d, MoneyToday today, BigDecimal expensesTotal, int occupied, int rooms, BigDecimal variance, String closedBy, String hotelId) {
        try {
            PeerServices.Business b = peers.business(a.getBusinessId()).orElse(null);
            if (b == null) {
                return List.of();
            }
            String hotel = peers.hotels(a.getBusinessId()).stream().filter(h -> hotelId.equals(h.id())).map(PeerServices.Hotel::name).findFirst().orElse("your hotel");
            String subject = hotel + " · day closed " + d + " · revenue " + today.revenue().toPlainString();
            String text = "Day " + d + " closed at " + hotel + " by " + closedBy + ".\n"
                    + "Revenue " + today.revenue().toPlainString() + " (" + today.paymentsCount() + " payments), expenses " + Money.of(expensesTotal).toPlainString()
                    + ", net " + Money.of(today.revenue().subtract(expensesTotal)).toPlainString() + ".\n"
                    + "Occupancy " + occupied + " of " + rooms + " rooms" + (rooms > 0 ? " (" + MoneyService.pct(occupied, rooms) + "%)" : "") + ".\n"
                    + (variance == null ? "Cash was not counted." : variance.signum() == 0 ? "Cash balanced." : "Cash " + (variance.signum() < 0 ? "short " : "over ") + variance.abs().toPlainString() + ".");
            return notifier.send(b.ownerEmail(), b.ownerPhone(), subject, text, Delivery.Kind.DAILY_REPORT, a.getId());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the night audit report", e);
        }
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }
}
