package com.hospio.fnb.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.dto.SaleDetail;
import com.hospio.fnb.dto.SalesHistory;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.rules.Days;
import com.hospio.fnb.rules.MenuChoices;
import com.hospio.fnb.rules.OutletAccess;
import com.hospio.fnb.rules.SaleBucket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bar and kitchen sales history: who may see which outlets, the filtered and paged list with its money, the CSV export, the unpaid count
 * for the sidebar and one sale in detail. Bar staff see bar outlets, kitchen staff the kitchen and restaurant ones; managers, owners and
 * finance see every outlet. Edit-level staff start on their own sales and may widen to the whole outlet; they may look at other people's
 * sales but only act on their own (Full access and managers act on any).
 */
@Service
@RequiredArgsConstructor
public class SalesHistoryService {

    public static final int MAX_DAYS = 93;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int EXPORT_LIMIT = 5000;

    /** The query string: dates are Lagos days (both inclusive); {@code desk} BAR or KITCHEN narrows to that desk's outlets. */
    public record Params(LocalDate from, LocalDate to, String outletId, String desk, String status, String method, String attendant, Boolean mine,
                         String q, Integer page, Integer size) {
    }

    public record Export(String filename, String csv, int rows, boolean truncated) {
    }

    public record UnpaidCount(long count, BigDecimal amount) {
    }

    private final OutletRepository outlets;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderPaymentRepository payments;
    private final MenuItemRepository items;
    private final OutletSettings settings;
    private final SalesHistoryQuery query;

    // ---- who sees what ----------------------------------------------------------------------------------------

    /** The caller's outlets for this request and what they may do there. */
    record Scope(HospioUserDetails user, String hotelId, List<Outlet> outlets, boolean management, boolean anyFull) {
        boolean mineByDefault() {
            return !management && !anyFull;
        }

        boolean actsOnOthers() {
            return anyFull || OutletAccess.manager(user);
        }
    }

    static boolean management(HospioUserDetails u) {
        return u.isPlatform() || u.isManagerOrOwner() || u.has(Role.FINANCE);
    }

    /** Staff see only outlets of their own role, as on close shift; management sees all. {@code desk} narrows to the bar or the kitchen. */
    Scope scope(String desk) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        boolean mgmt = management(user);
        String d = desk == null ? "" : desk.trim().toUpperCase(Locale.ROOT);
        if (!d.isEmpty() && !d.equals("BAR") && !d.equals("KITCHEN")) {
            throw new BadRequestException("desk is BAR or KITCHEN");
        }
        List<Outlet> visible = outlets.findByHotelIdOrderByName(hotelId).stream()
                .filter(o -> OutletAccess.sees(user, o) && (mgmt || user.has(OutletAccess.roleFor(o))))
                .filter(o -> d.isEmpty() || (d.equals("BAR") == (o.getKind() == Outlet.Kind.BAR)))
                .toList();
        boolean anyFull = visible.stream().anyMatch(o -> OutletAccess.full(user, o));
        return new Scope(user, hotelId, visible, mgmt, anyFull);
    }

    // ---- the list -----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SalesHistory history(Params p) {
        Scope s = scope(p.desk());
        Resolved r = resolve(s, p, false);
        int size = p.size() == null ? 25 : p.size();
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size is 1 to " + MAX_PAGE_SIZE);
        }
        int page = p.page() == null ? 0 : p.page();
        if (page < 0) {
            throw new BadRequestException("page starts at 0");
        }
        List<String> ids = query.pageIds(r.filter(), page, size);
        long matching = query.count(r.filter());
        SalesHistoryQuery.Money3 t = query.totals(r.filter());
        Map<String, Outlet> byId = s.outlets().stream().collect(Collectors.toMap(Outlet::getId, Function.identity()));
        List<SalesHistory.OutletTotals> perOutlet = query.byOutlet(r.filter()).stream().map(g -> {
            Outlet o = byId.get(g.key());
            SalesHistoryQuery.Money3 m = g.money();
            return new SalesHistory.OutletTotals(g.key(), o == null ? g.name() : o.getName(), o == null ? null : o.getKind().name(), m.sales(),
                    m.sold(), m.paid(), m.unpaid(), m.onRooms(), m.cancelled());
        }).toList();
        List<SalesHistory.AttendantTotals> perAttendant = query.byAttendant(r.filter()).stream().map(g -> {
            SalesHistoryQuery.Money3 m = g.money();
            return new SalesHistory.AttendantTotals(g.key(), g.name() == null || g.name().isBlank() ? "Not recorded" : g.name(), m.sales(),
                    m.sold(), m.paid(), m.unpaid(), m.onRooms(), m.cancelled());
        }).toList();
        SalesHistoryQuery.Earlier e = query.earlierUnpaid(r.filter());
        return new SalesHistory(r.from(), r.to(), page, size, matching, rows(ids, byId),
                new SalesHistory.Totals(t.sales(), t.sold(), t.paid(), t.unpaid(), t.onRooms(), t.cancelled(), t.cancelledAmount()),
                perOutlet, perAttendant, new SalesHistory.EarlierUnpaid(e.count(), e.amount(), e.oldest()),
                new SalesHistory.Viewer(s.user().getUserId(), s.management(), r.mine(), s.mineByDefault(), canExport(s), s.actsOnOthers()),
                s.outlets().stream().map(o -> new SalesHistory.OutletRef(o.getId(), o.getName(), o.getKind().name())).toList());
    }

    static boolean canExport(Scope s) {
        return s.management() || s.anyFull();
    }

    /** Every matching sale as CSV (up to {@link #EXPORT_LIMIT}), at the outlets where the caller has Full access or management. */
    @Transactional(readOnly = true)
    public Export export(Params p) {
        Scope s = scope(p.desk());
        if (!canExport(s)) {
            throw new ForbiddenException("Exporting sales needs Full access or a manager");
        }
        List<Outlet> exportable = s.management() ? s.outlets() : s.outlets().stream().filter(o -> OutletAccess.full(s.user(), o)).toList();
        Scope narrowed = new Scope(s.user(), s.hotelId(), exportable, s.management(), s.anyFull());
        Resolved r = resolve(narrowed, p, true);
        List<String> ids = query.pageIds(r.filter(), 0, EXPORT_LIMIT + 1);
        boolean truncated = ids.size() > EXPORT_LIMIT;
        if (truncated) {
            ids = ids.subList(0, EXPORT_LIMIT);
        }
        Map<String, Outlet> byId = exportable.stream().collect(Collectors.toMap(Outlet::getId, Function.identity()));
        List<SalesHistory.Row> rows = rows(ids, byId);
        StringBuilder csv = new StringBuilder("Number,Date,Time,Outlet,Seat or room,Guest,Items,Opened by,Status,Total,Paid,Balance,Methods,Settled by,Cancel reason\n");
        DateTimeFormatter date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(Days.LAGOS);
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm").withZone(Days.LAGOS);
        for (SalesHistory.Row row : rows) {
            String where = row.roomNumber() != null ? "Room " + row.roomNumber() : row.tableNo() != null ? row.tableNo() : kindLabel(row.kind());
            csv.append(String.join(",", cell(row.number()), cell(date.format(row.createdAt())), cell(time.format(row.createdAt())),
                    cell(row.outletName()), cell(where), cell(row.guestName()), cell(row.items()), cell(row.openedByName()),
                    cell(statusLabel(row.status())), plain(row.total()), plain(Money.of(row.total().subtract(row.balance()))), plain(row.balance()),
                    cell(row.methods().stream().map(SalesHistoryService::methodLabel).collect(Collectors.joining(" + "))), cell(row.settledByName()),
                    cell(row.cancelReason()))).append('\n');
        }
        String name = "sales-" + r.from() + (r.to().equals(r.from()) ? "" : "-to-" + r.to()) + ".csv";
        return new Export(name, csv.toString(), rows.size(), truncated);
    }

    /** Sales still owing money (to pay or part paid), any day, in the caller's default view: the sidebar badge. */
    @Transactional(readOnly = true)
    public UnpaidCount unpaid(String desk) {
        Scope s = scope(desk);
        String attendant = s.mineByDefault() ? s.user().getUserId() : null;
        SalesHistoryQuery.Filter f = new SalesHistoryQuery.Filter(s.hotelId(), s.outlets().stream().map(Outlet::getId).toList(), null, null,
                SaleBucket.UNPAID, null, attendant, null, null);
        SalesHistoryQuery.Money3 m = query.totals(f);
        return new UnpaidCount(m.sales(), m.unpaid());
    }

    record Resolved(SalesHistoryQuery.Filter filter, LocalDate from, LocalDate to, boolean mine) {
    }

    private Resolved resolve(Scope s, Params p, boolean exporting) {
        LocalDate from = p.from() == null ? (p.to() == null ? Days.today() : p.to()) : p.from();
        LocalDate to = p.to() == null ? from : p.to();
        if (to.isBefore(from)) {
            throw new BadRequestException("The end date is before the start date");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BadRequestException("Choose up to " + MAX_DAYS + " days at a time");
        }
        List<String> all = s.outlets().stream().map(Outlet::getId).toList();
        List<String> wanted = all;
        if (p.outletId() != null && !p.outletId().isBlank()) {
            if (!all.contains(p.outletId())) {
                throw new ForbiddenException(exporting ? "You can't export that outlet's sales" : "That outlet's sales aren't yours to see");
            }
            wanted = List.of(p.outletId());
        }
        String method = p.method() == null || p.method().isBlank() ? null : p.method().trim().toUpperCase(Locale.ROOT);
        if (method != null && !SalesHistoryQuery.METHODS.contains(method)) {
            throw new BadRequestException("Unknown payment method " + p.method().trim() + ". Use CASH, CARD, TRANSFER or ROOM");
        }
        Set<SaleBucket> buckets = SaleBucket.parseFilter(p.status());
        boolean mine = p.mine() == null ? s.mineByDefault() : p.mine();
        String attendant = mine ? s.user().getUserId() : p.attendant() == null || p.attendant().isBlank() ? null : p.attendant().trim();
        if ("none".equalsIgnoreCase(attendant)) {
            attendant = "";
        }
        String q = p.q() == null || p.q().isBlank() ? null : p.q().trim();
        if (q != null && q.length() > 80) {
            throw new BadRequestException("Search for up to 80 characters");
        }
        return new Resolved(new SalesHistoryQuery.Filter(s.hotelId(), wanted, Days.start(from), Days.end(to), buckets, method, attendant, q, all),
                from, to, mine);
    }

    /** The page's orders in the page's order, with item summaries and payment methods. */
    private List<SalesHistory.Row> rows(List<String> ids, Map<String, Outlet> outletsById) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Order> byId = new LinkedHashMap<>();
        for (List<String> chunk : chunks(ids)) {
            orders.findAllById(chunk).forEach(o -> byId.put(o.getId(), o));
        }
        Map<String, List<OrderLine>> linesBy = new LinkedHashMap<>();
        Map<String, List<OrderPayment>> paysBy = new LinkedHashMap<>();
        for (List<String> chunk : chunks(ids)) {
            lines.findByOrderIdIn(chunk).forEach(l -> linesBy.computeIfAbsent(l.getOrderId(), k -> new ArrayList<>()).add(l));
            payments.findByOrderIdIn(chunk).forEach(x -> paysBy.computeIfAbsent(x.getOrderId(), k -> new ArrayList<>()).add(x));
        }
        List<SalesHistory.Row> out = new ArrayList<>();
        for (String id : ids) {
            Order o = byId.get(id);
            if (o != null) {
                out.add(row(o, outletsById.get(o.getOutletId()), linesBy.getOrDefault(id, List.of()), paysBy.getOrDefault(id, List.of())));
            }
        }
        return out;
    }

    private static List<List<String>> chunks(List<String> ids) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) {
            out.add(ids.subList(i, Math.min(ids.size(), i + 500)));
        }
        return out;
    }

    static SalesHistory.Row row(Order o, Outlet outlet, List<OrderLine> orderLines, List<OrderPayment> orderPayments) {
        Outlet.Kind kind = outlet == null ? Outlet.Kind.KITCHEN : outlet.getKind();
        SaleBucket b = SaleBucket.of(o, kind);
        SaleBucket.Parts parts = SaleBucket.parts(o);
        List<String> methods = new ArrayList<>(orderPayments.stream().sorted(Comparator.comparing(OrderPayment::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))).map(OrderPayment::getMethod).distinct().toList());
        if (o.isChargedToRoom()) {
            methods.add("ROOM");
        }
        Summary sum = summary(orderLines);
        return new SalesHistory.Row(o.getId(), o.getNumber(), o.getOutletId(), outlet == null ? null : outlet.getName(), kind.name(), o.getKind(),
                o.getTableNo(), o.getRoomNumber(), o.getGuestName(), o.getCreatedAt(), o.getOpenedBy(), o.getOpenedByName(), sum.text(), sum.count(),
                o.getTotal(), o.getPaidAmount(), Money.of(parts.unpaid()), b.name(), o.getStatus(), methods, o.getSettledAt(), o.getSettledBy(),
                o.getSettledByName(), o.getCancelReason());
    }

    record Summary(String text, int count) {
    }

    /** "3 Coca-Cola, 2 Orange juice": live lines by name in the order first added; all lines when every one was cancelled. */
    static Summary summary(List<OrderLine> orderLines) {
        List<OrderLine> sorted = orderLines.stream().sorted(Comparator.comparing(OrderLine::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<OrderLine> live = sorted.stream().filter(OrderLine::isLive).toList();
        List<OrderLine> use = live.isEmpty() ? sorted : live;
        Map<String, Integer> qty = new LinkedHashMap<>();
        for (OrderLine l : use) {
            qty.merge(l.getName(), l.getQuantity(), Integer::sum);
        }
        String text = qty.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        return new Summary(text, live.stream().mapToInt(OrderLine::getQuantity).sum());
    }

    // ---- one sale ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SaleDetail detail(String id) {
        Scope s = scope(null);
        Order o = orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sale not found"));
        Outlet outlet = s.outlets().stream().filter(x -> x.getId().equals(o.getOutletId())).findFirst()
                .orElseThrow(() -> new ForbiddenException("This sale belongs to another outlet"));
        List<OrderLine> all = lines.findByOrderIdOrderByCreatedAt(id);
        List<OrderPayment> paid = payments.findByOrderIdIn(List.of(id)).stream()
                .sorted(Comparator.comparing(OrderPayment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        Map<String, MenuItem> menu = items.findAllById(all.stream().map(OrderLine::getMenuItemId).distinct().toList()).stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        SalesHistory.Row row = row(o, outlet, all, paid);
        List<SaleDetail.Line> detailLines = all.stream().map(l -> {
            MenuItem m = menu.get(l.getMenuItemId());
            List<SaleDetail.Choice> choices = m == null ? List.of() : MenuChoices.chosenWithExtra(m.getOptions(), l.getOptions()).stream()
                    .map(c -> new SaleDetail.Choice(c.getKey(), Money.of(c.getValue()))).toList();
            BigDecimal discount = l.getDiscount() == null ? BigDecimal.ZERO : l.getDiscount();
            return new SaleDetail.Line(l.getId(), l.getName(), l.getQuantity(), l.getUnitPrice(), l.getOptions(), choices, l.getNotes(),
                    Money.of(discount), Money.of(l.lineTotal().subtract(discount)), l.getStatus().name(), l.getCancelReason(), l.getCancelledByName());
        }).toList();
        List<SaleDetail.Payment> pays = paid.stream().map(x -> new SaleDetail.Payment(x.getPaymentId(), x.getMethod(), x.getAmount(), x.getCreatedAt(),
                x.getReference(), x.getTakenByName())).toList();
        SaleDetail.Timeline timeline = new SaleDetail.Timeline(o.getCreatedAt(), o.getOpenedByName(), o.getSentAt(), o.getSettledAt(), o.getSettledBy(),
                o.getSettledByName(), o.getClosedAt(), o.getClosedByName());
        return new SaleDetail(row, o.getRoomId(), o.getBookingId(), o.getTableId(), o.getNotes(), o.getSubtotal(), o.getDiscount(), o.getTax(),
                o.getTotal(), o.getPaidAmount(), row.balance(), detailLines, pays, timeline, can(s, o, outlet, all));
    }

    /**
     * What the caller may do on the sale, by the order rules: take payment or charge the room while it is open and owes money (anyone who
     * works the outlet, on their own sales; Full access and managers on any); cancel only with nothing paid, by whoever took it while
     * nothing was sent, by a manager after.
     */
    SaleDetail.Can can(Scope s, Order o, Outlet outlet, List<OrderLine> all) {
        HospioUserDetails u = s.user();
        boolean live = o.getStatus() != Order.Status.CLOSED && o.getStatus() != Order.Status.CANCELLED && !o.isSettled();
        if (!live) {
            return new SaleDetail.Can(false, false, false, null);
        }
        boolean works = OutletAccess.works(u, outlet);
        if (!works) {
            return new SaleDetail.Can(false, false, false, "View only");
        }
        boolean own = u.getUserId() != null && u.getUserId().equals(o.getOpenedBy());
        boolean any = OutletAccess.full(u, outlet);
        if (!own && !any) {
            String who = o.getOpenedByName() == null ? "someone else" : o.getOpenedByName();
            return new SaleDetail.Can(false, false, false, "Opened by " + who + ". Only they, someone with Full access or a manager can settle or cancel it");
        }
        BigDecimal balance = o.getTotal().subtract(o.getPaidAmount() == null ? BigDecimal.ZERO : o.getPaidAmount());
        boolean owes = balance.signum() > 0;
        boolean roomOn = settings.get(o.getHotelId()).roomCharge();
        boolean nothingPaid = o.getPaidAmount() == null || o.getPaidAmount().signum() <= 0;
        boolean untouched = o.getStatus() == Order.Status.OPEN
                && all.stream().allMatch(l -> l.getStatus() == OrderLine.Status.NEW || l.getStatus() == OrderLine.Status.CANCELLED);
        boolean cancel = nothingPaid && (untouched || OutletAccess.manager(u));
        String note = null;
        if (!nothingPaid) {
            note = "Part paid: collect the balance. Cancelling needs the payment refunded first";
        } else if (!cancel) {
            note = "Something on it was already sent. Only a manager can cancel it";
        } else if (!roomOn && owes) {
            note = "Charging to a room is switched off for this hotel";
        }
        return new SaleDetail.Can(owes, owes && roomOn, cancel, note);
    }

    // ---- labels ---------------------------------------------------------------------------------------------

    static String statusLabel(String bucket) {
        return switch (bucket) {
            case "PAID" -> "Paid";
            case "TO_PAY" -> "To pay";
            case "PART_PAID" -> "Part paid";
            case "ON_ROOM" -> "On a room";
            case "CANCELLED" -> "Cancelled";
            default -> "In progress";
        };
    }

    static String methodLabel(String m) {
        return switch (m) {
            case "CASH" -> "Cash";
            case "CARD" -> "Card";
            case "TRANSFER" -> "Transfer";
            case "ROOM" -> "Room";
            default -> m.charAt(0) + m.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    static String kindLabel(Order.Kind k) {
        return switch (k) {
            case TAB -> "Tab";
            case TAKEAWAY -> "Takeaway";
            case ROOM_SERVICE -> "Room service";
            default -> "Walk-up";
        };
    }

    /** A CSV cell: quoted when it holds a comma, quote or line break; a leading = + - @ is defused for spreadsheets. */
    static String cell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : Money.of(v).toPlainString();
    }
}
