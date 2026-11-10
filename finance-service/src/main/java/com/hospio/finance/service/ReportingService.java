package com.hospio.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.finance.client.PeerServices;
import com.hospio.finance.entity.BankDeposit;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.TillEntry;
import com.hospio.finance.repository.BankDepositRepository;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.RevenueEntryRepository;
import com.hospio.finance.repository.TillEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The numbers over time: daily series, the day's transactions, and the owner's view across hotels. */
@Service
@RequiredArgsConstructor
public class ReportingService {

    private static final int MAX_DAYS = 366;

    private final RevenueEntryRepository revenue;
    private final ExpenseRepository expenses;
    private final BankDepositRepository deposits;
    private final TillEntryRepository tillEntries;
    private final PeerServices peers;
    private final ExpenseCategories categories;

    /** What the restaurant and bar sold on a day, by outlet, as fnb-service reports it (room charges included). */
    public JsonNode outletSales(LocalDate date) {
        return peers.outletSales(TenantContext.requireHotel(), date == null ? LocalDate.now(MoneyService.LAGOS) : date)
                .orElseThrow(() -> new ExternalServiceException("Outlet sales can't be read right now"));
    }

    public record DayPoint(LocalDate date, BigDecimal revenue, BigDecimal expenses) {
    }

    public record Transaction(Instant at, String kind, String description, BigDecimal amount, String method, String by) {
    }

    public record HotelLine(String hotelId, String name, BigDecimal revenue, BigDecimal expenses, BigDecimal profit, Double occupancyPct) {
    }

    public record Totals(BigDecimal revenue, BigDecimal expenses, BigDecimal profit, Double occupancyPct) {
    }

    public record OwnerSummary(LocalDate from, LocalDate to, List<HotelLine> hotels, Totals totals) {
    }

    @Transactional(readOnly = true)
    public List<DayPoint> series(LocalDate from, LocalDate to) {
        range(from, to);
        String hotelId = TenantContext.requireHotel();
        Map<LocalDate, BigDecimal> in = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        revenue.findByHotelIdAndBusinessDateBetween(hotelId, from, to).forEach(r -> in.merge(r.getBusinessDate(), r.getAmount(), BigDecimal::add));
        expenses.findByHotelIdAndExpenseDateBetween(hotelId, from, to).stream().filter(e -> e.getStatus() != Expense.Status.DECLINED)
                .forEach(e -> out.merge(e.getExpenseDate(), e.getAmount(), BigDecimal::add));
        List<DayPoint> points = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            points.add(new DayPoint(d, Money.of(in.get(d)), Money.of(out.get(d))));
        }
        return points;
    }

    public record IncomeSource(String source, BigDecimal amount, int pct) {
    }

    /** Where the money came from between two dates: revenue by the desk that took it, largest first, with each share of the total. */
    @Transactional(readOnly = true)
    public List<IncomeSource> income(LocalDate from, LocalDate to) {
        range(from, to);
        Map<String, BigDecimal> by = new LinkedHashMap<>();
        revenue.findByHotelIdAndBusinessDateBetween(TenantContext.requireHotel(), from, to)
                .forEach(r -> by.merge(sourceOf(r.getRole()), r.getAmount(), BigDecimal::add));
        BigDecimal total = by.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return by.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new IncomeSource(e.getKey(), Money.of(e.getValue()),
                        total.signum() == 0 ? 0 : e.getValue().multiply(BigDecimal.valueOf(100)).divide(total, 0, java.math.RoundingMode.HALF_UP).intValue()))
                .toList();
    }

    private static String sourceOf(String role) {
        if (role == null) {
            return "Other";
        }
        return switch (role) {
            case "FRONT_OFFICE", "MANAGER", "OWNER" -> "Rooms & front desk";
            case "BAR" -> "Bar";
            case "KITCHEN" -> "Kitchen & restaurant";
            case "LAUNDRY" -> "Laundry";
            default -> "Other";
        };
    }

    /** Payments, expenses paid, bank deposits and till movements for a day, newest first. Money leaving the hotel's cash is negative. */
    @Transactional(readOnly = true)
    public List<Transaction> transactions(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(MoneyService.LAGOS) : date;
        Instant from = d.atStartOfDay(MoneyService.LAGOS).toInstant();
        Instant to = d.plusDays(1).atStartOfDay(MoneyService.LAGOS).toInstant();
        List<Transaction> list = new ArrayList<>();
        for (RevenueEntry r : revenue.findByHotelIdAndBusinessDate(hotelId, d)) {
            list.add(new Transaction(r.getCreatedAt(), "PAYMENT", r.isUnattachedCash() ? "Payment (no till open)" : "Payment", r.getAmount(), r.getMethod(), r.getRole()));
        }
        List<Expense> paid = new ArrayList<>(expenses.findByHotelIdAndPaidAtBetween(hotelId, from, to));
        expenses.findByHotelIdAndExpenseDateBetween(hotelId, d, d).stream().filter(e -> e.getStatus() == Expense.Status.PAID && e.getPaidAt() == null).forEach(paid::add);
        Map<String, String> labels = paid.isEmpty() ? Map.of() : categories.labels(hotelId);
        for (Expense e : paid) {
            Instant at = e.getPaidAt() != null ? e.getPaidAt() : from;
            list.add(new Transaction(at, "EXPENSE", ExpenseCategories.labelOf(labels, e.getCategory()) + " · " + e.getDescription(), Money.of(e.getAmount()).negate(), e.getPaidFrom(),
                    e.getPaidByName() != null ? e.getPaidByName() : e.getRequestedByName()));
        }
        for (BankDeposit b : deposits.findByHotelIdAndBankedAtBetween(hotelId, from, to)) {
            list.add(new Transaction(b.getBankedAt(), "DEPOSIT", "Banked" + (b.getBankAccount() == null ? "" : " to " + b.getBankAccount()) + (b.getReference() == null ? "" : " · " + b.getReference()),
                    Money.of(b.getAmount()).negate(), "CASH", b.getBankedByName()));
        }
        for (TillEntry t : tillEntries.findByHotelIdAndCreatedAtBetweenOrderByCreatedAtDesc(hotelId, from, to)) {
            // Payments are already listed from the ledger and a cash expense from the expense itself.
            if (t.getKind() == TillEntry.Kind.PAYMENT || t.getKind() == TillEntry.Kind.EXPENSE) {
                continue;
            }
            list.add(new Transaction(t.getCreatedAt(), t.getKind().name(), t.getDescription(), t.getAmount(), t.getMethod(), t.getCreatedByName()));
        }
        list.sort(Comparator.comparing(Transaction::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    /** Each hotel of the business as the system sees it, plus totals. Occupancy is what is in house now over the hotel's rooms. */
    @Transactional(readOnly = true)
    public OwnerSummary ownerSummary(LocalDate from, LocalDate to) {
        range(from, to);
        HospioUserDetails user = CurrentUser.require();
        String businessId = TenantContext.requireBusiness();
        List<HotelLine> lines = new ArrayList<>();
        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        int occupiedAll = 0;
        int roomsAll = 0;
        for (PeerServices.Hotel h : peers.hotels(businessId)) {
            Tenant asHotel = Tenant.systemFor(businessId, h.id(), user.getUserId(), user.getFullName());
            BigDecimal in = TenantContext.runWith(asHotel, () -> revenue.findByHotelIdAndBusinessDateBetween(h.id(), from, to).stream()
                    .map(RevenueEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal out = TenantContext.runWith(asHotel, () -> expenses.findByHotelIdAndExpenseDateBetween(h.id(), from, to).stream()
                    .filter(e -> e.getStatus() != Expense.Status.DECLINED).map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            int rooms = peers.roomCount(h.id());
            int occupied = peers.daySoft(h.id(), to).map(PeerServices.Day::roomsOccupied).orElse(0);
            lines.add(new HotelLine(h.id(), h.name(), Money.of(in), Money.of(out), Money.of(in.subtract(out)), rooms > 0 ? MoneyService.pct(occupied, rooms) : null));
            totalIn = totalIn.add(in);
            totalOut = totalOut.add(out);
            if (rooms > 0) {
                roomsAll += rooms;
                occupiedAll += occupied;
            }
        }
        return new OwnerSummary(from, to, lines, new Totals(Money.of(totalIn), Money.of(totalOut), Money.of(totalIn.subtract(totalOut)),
                roomsAll > 0 ? MoneyService.pct(occupiedAll, roomsAll) : null));
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BadRequestException("Choose a from and a to date, with to on or after from");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BadRequestException("Choose a range of a year or less");
        }
    }
}
