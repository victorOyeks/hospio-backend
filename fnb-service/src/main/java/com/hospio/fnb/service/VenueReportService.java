package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.util.Money;
import com.hospio.fnb.dto.SalesHistory;
import com.hospio.fnb.dto.VenueReport;
import com.hospio.fnb.dto.VenueToday;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.PriceChangeRequestRepository;
import com.hospio.fnb.rules.Days;
import com.hospio.fnb.rules.Recipes;
import com.hospio.fnb.rules.SaleBucket;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Venue dashboards and reports for managers, owners and finance (docs/plan-venue-type.md phase 2): every outlet of the active location,
 * real data only. Bar and kitchen staff keep their own close-shift summary and sales history.
 */
@Service
@RequiredArgsConstructor
public class VenueReportService {

    public static final int MAX_DAYS = SalesHistoryService.MAX_DAYS;
    static final int LIST_LIMIT = 500;
    static final int DEFAULT_STALE_HOURS = 4;

    private final SalesHistoryService history;
    private final SalesHistoryQuery sales;
    private final VenueReportQuery query;
    private final MenuItemRepository menu;
    private final PriceChangeRequestRepository priceChanges;

    /** Management's outlets of the active location (all of them), or 403. */
    private SalesHistoryService.Scope scope() {
        SalesHistoryService.Scope s = history.scope(null);
        if (!s.management()) {
            throw new ForbiddenException("The venue reports are for managers, owners and finance");
        }
        return s;
    }

    @Transactional(readOnly = true)
    public VenueReport report(LocalDate from, LocalDate to, String outletId) {
        SalesHistoryService.Scope s = scope();
        LocalDate f = from == null ? (to == null ? Days.today() : to) : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) {
            throw new BadRequestException("The end date is before the start date");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            throw new BadRequestException("Choose up to " + MAX_DAYS + " days at a time");
        }
        List<String> all = s.outlets().stream().map(Outlet::getId).toList();
        List<String> wanted = all;
        String outlet = outletId == null || outletId.isBlank() ? null : outletId.trim();
        if (outlet != null) {
            if (!all.contains(outlet)) {
                throw new ForbiddenException("That outlet isn't at this location");
            }
            wanted = List.of(outlet);
        }
        Instant start = Days.start(f);
        Instant end = Days.end(t);
        VenueReportQuery.Scope q = new VenueReportQuery.Scope(s.hotelId(), wanted, start, end);
        SalesHistoryQuery.Filter filter = new SalesHistoryQuery.Filter(s.hotelId(), wanted, start, end, EnumSet.allOf(SaleBucket.class), null, null,
                null, wanted);

        SalesHistoryQuery.Money3 m = sales.totals(filter);
        VenueReportQuery.Extras x = query.extras(q);
        List<VenueReport.ItemRow> items = query.items(q, false, 0);
        VenueReportQuery.ItemTotals it = query.itemTotals(q);
        List<VenueReportQuery.VoidTotal> voidTotals = query.voidTotals(q);
        long voids = voidTotals.stream().mapToLong(VenueReportQuery.VoidTotal::count).sum();
        BigDecimal voidAmount = voidTotals.stream().map(VenueReportQuery.VoidTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        VenueReport.Totals totals = new VenueReport.Totals(m.sales(), m.sold(), m.paid(), m.unpaid(), m.onRooms(), m.cancelled(), m.cancelledAmount(),
                x.discounts(), x.serviceCharge(), x.tax(), it.quantity(), Money.of(it.revenue()), voids, Money.of(voidAmount), x.complimentary(),
                x.complimentaryAmount());

        Map<String, Outlet> byId = s.outlets().stream().collect(Collectors.toMap(Outlet::getId, Function.identity()));
        List<VenueReport.OutletRow> byOutlet = sales.byOutlet(filter).stream().map(g -> outletRow(g, byId.get(g.key()))).toList();
        List<VenueReport.StaffRow> byStaff = staff(sales.byAttendant(filter), voidTotals);

        List<VenueReport.VoidRow> voidRows = query.voids(q, LIST_LIMIT + 1);
        List<VenueReport.DiscountRow> discounts = query.discounts(q, LIST_LIMIT + 1);
        List<VenueReport.MethodRow> methods = new ArrayList<>(query.taken(q));
        if (m.onRooms().signum() > 0) {
            methods.add(new VenueReport.MethodRow("ROOM", 0, m.onRooms()));
        }
        Expected expected = expected(items);
        List<SalesHistory.OutletRef> refs = s.outlets().stream().map(o -> new SalesHistory.OutletRef(o.getId(), o.getName(), o.getKind().name())).toList();
        return new VenueReport(f, t, outlet, refs, totals, byOutlet, items, categories(items), byStaff, hours(query.hours(q)), methods,
                cap(voidRows), voidRows.size() > LIST_LIMIT, cap(discounts), discounts.size() > LIST_LIMIT, expected.use(), expected.withRecipe(),
                expected.withoutRecipe());
    }

    @Transactional(readOnly = true)
    public VenueToday today(Integer staleHours) {
        SalesHistoryService.Scope s = scope();
        int stale = staleHours == null ? DEFAULT_STALE_HOURS : staleHours;
        if (stale < 1 || stale > 72) {
            throw new BadRequestException("staleHours is 1 to 72");
        }
        LocalDate day = Days.today();
        Instant now = Instant.now();
        Instant start = Days.start(day);
        List<String> ids = s.outlets().stream().map(Outlet::getId).toList();
        VenueReportQuery.Scope q = new VenueReportQuery.Scope(s.hotelId(), ids, start, Days.end(day));
        SalesHistoryQuery.Money3 m = sales.totals(filter(s.hotelId(), ids, start, Days.end(day)));
        VenueReportQuery.ItemTotals it = query.itemTotals(q);
        VenueToday.Day today = new VenueToday.Day(m.sales(), m.sold(), m.paid(), m.unpaid(), m.cancelled(), it.quantity());

        LocalDate weekAgo = day.minusDays(7);
        Instant weekStart = Days.start(weekAgo);
        SalesHistoryQuery.Money3 whole = sales.totals(filter(s.hotelId(), ids, weekStart, Days.end(weekAgo)));
        VenueToday.LastWeek lastWeek = null;
        if (whole.sales() > 0) {
            Instant sameTime = weekStart.plus(Duration.between(start, now));
            SalesHistoryQuery.Money3 soFar = sales.totals(filter(s.hotelId(), ids, weekStart, sameTime));
            lastWeek = new VenueToday.LastWeek(weekAgo, soFar.sales(), soFar.sold(), whole.sales(), whole.sold());
        }

        Map<String, Outlet> byId = s.outlets().stream().collect(Collectors.toMap(Outlet::getId, Function.identity()));
        List<VenueReport.OutletRow> byOutlet = sales.byOutlet(filter(s.hotelId(), ids, start, Days.end(day))).stream()
                .map(g -> outletRow(g, byId.get(g.key()))).toList();
        SalesHistoryQuery.Money3 unpaid = sales.totals(new SalesHistoryQuery.Filter(s.hotelId(), ids, null, null, SaleBucket.UNPAID, null, null, null,
                null));
        List<VenueReportQuery.VoidTotal> vt = query.voidTotals(q);
        VenueToday.Voids voids = new VenueToday.Voids(vt.stream().mapToLong(VenueReportQuery.VoidTotal::count).sum(),
                Money.of(vt.stream().map(VenueReportQuery.VoidTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add)), query.voids(q, 10));
        return new VenueToday(day, now, today, lastWeek, query.taken(q), byOutlet, query.items(q, true, 5),
                query.openTabs(s.hotelId(), ids, now.minus(Duration.ofHours(stale)), stale, 10), new VenueToday.Unpaid(unpaid.sales(), unpaid.unpaid()),
                voids, priceChanges.countByHotelIdAndStatus(s.hotelId(), "PENDING"));
    }

    // ---- shaping ------------------------------------------------------------------------------------------

    private static SalesHistoryQuery.Filter filter(String hotelId, List<String> ids, Instant from, Instant to) {
        return new SalesHistoryQuery.Filter(hotelId, ids, from, to, EnumSet.allOf(SaleBucket.class), null, null, null, ids);
    }

    private static VenueReport.OutletRow outletRow(SalesHistoryQuery.Group g, Outlet o) {
        SalesHistoryQuery.Money3 m = g.money();
        return new VenueReport.OutletRow(g.key(), o == null ? g.name() : o.getName(), o == null ? null : o.getKind().name(), m.sales(), m.sold(),
                m.paid(), m.unpaid(), m.onRooms(), m.cancelled());
    }

    /** Sellers with their voids; someone who only voided (a manager) still gets a row. Biggest seller first. */
    static List<VenueReport.StaffRow> staff(List<SalesHistoryQuery.Group> sellers, List<VenueReportQuery.VoidTotal> voids) {
        Map<String, VenueReportQuery.VoidTotal> v = voids.stream().collect(Collectors.toMap(VenueReportQuery.VoidTotal::byId, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
        List<VenueReport.StaffRow> out = new ArrayList<>();
        for (SalesHistoryQuery.Group g : sellers) {
            SalesHistoryQuery.Money3 m = g.money();
            VenueReportQuery.VoidTotal mine = v.remove(g.key() == null ? "" : g.key());
            out.add(new VenueReport.StaffRow(g.key(), name(g.name()), m.sales(), m.sold(), m.paid(), m.cancelled(), mine == null ? 0 : mine.count(),
                    mine == null ? Money.of(BigDecimal.ZERO) : Money.of(mine.amount())));
        }
        for (VenueReportQuery.VoidTotal only : v.values()) {
            out.add(new VenueReport.StaffRow(only.byId(), name(only.by()), 0, Money.of(BigDecimal.ZERO), Money.of(BigDecimal.ZERO), 0, only.count(),
                    Money.of(only.amount())));
        }
        out.sort(Comparator.comparing(VenueReport.StaffRow::sold).reversed().thenComparing(VenueReport.StaffRow::voids, Comparator.reverseOrder())
                .thenComparing(VenueReport.StaffRow::name));
        return out;
    }

    private static String name(String n) {
        return n == null || n.isBlank() ? "Not recorded" : n;
    }

    /** Items rolled up by menu category, biggest revenue first. */
    static List<VenueReport.CategoryRow> categories(List<VenueReport.ItemRow> items) {
        Map<String, long[]> qty = new LinkedHashMap<>();
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        for (VenueReport.ItemRow i : items) {
            long[] q = qty.computeIfAbsent(i.category(), k -> new long[2]);
            q[0] += i.quantity();
            q[1]++;
            revenue.merge(i.category(), i.revenue(), BigDecimal::add);
        }
        return qty.entrySet().stream().map(e -> new VenueReport.CategoryRow(e.getKey(), e.getValue()[0], Money.of(revenue.get(e.getKey())),
                        (int) e.getValue()[1]))
                .sorted(Comparator.comparing(VenueReport.CategoryRow::revenue).reversed().thenComparing(VenueReport.CategoryRow::category)).toList();
    }

    /** All 24 Lagos hours, zero where nothing was sold. */
    static List<VenueReport.HourRow> hours(List<VenueReport.HourRow> rows) {
        Map<Integer, VenueReport.HourRow> by = rows.stream().collect(Collectors.toMap(VenueReport.HourRow::hour, Function.identity()));
        List<VenueReport.HourRow> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            out.add(by.getOrDefault(h, new VenueReport.HourRow(h, 0, Money.of(BigDecimal.ZERO))));
        }
        return out;
    }

    record Expected(List<VenueReport.StockUse> use, int withRecipe, int withoutRecipe) {
    }

    /** What the items' recipes call for from stock, for the units sold (items without a recipe are only counted). */
    Expected expected(List<VenueReport.ItemRow> items) {
        if (items.isEmpty()) {
            return new Expected(List.of(), 0, 0);
        }
        Map<String, MenuItem> byId = menu.findAllById(items.stream().map(VenueReport.ItemRow::menuItemId).distinct().toList()).stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        return expected(items, id -> {
            MenuItem m = byId.get(id);
            return m == null ? List.of() : Recipes.read(m.getRecipeJson());
        });
    }

    static Expected expected(List<VenueReport.ItemRow> items, Function<String, List<Recipes.Line>> recipeOf) {
        Map<String, BigDecimal> use = new TreeMap<>();
        int with = 0;
        int without = 0;
        for (VenueReport.ItemRow i : items) {
            List<Recipes.Line> recipe = recipeOf.apply(i.menuItemId());
            if (recipe.isEmpty()) {
                without++;
                continue;
            }
            with++;
            for (Recipes.Line l : recipe) {
                if (l.stockItemId() != null && l.quantity() != null) {
                    use.merge(l.stockItemId(), l.quantity().multiply(BigDecimal.valueOf(i.quantity())), BigDecimal::add);
                }
            }
        }
        return new Expected(use.entrySet().stream().map(e -> new VenueReport.StockUse(e.getKey(), e.getValue().stripTrailingZeros())).toList(), with,
                without);
    }

    private static <T> List<T> cap(List<T> rows) {
        return rows.size() > LIST_LIMIT ? rows.subList(0, LIST_LIMIT) : rows;
    }
}
