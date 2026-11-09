package com.hospio.fnb.dto;

import com.hospio.common.util.Money;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.Outlet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What an outlet sold on a day, from the orders settled that day: {@code sales} is everything sold, {@code paidAtOutlet} the part paid
 * at the outlet, {@code chargedToRooms} the part put on a room (income for that arrives when the folio is paid), {@code byMethod} what
 * was received at the outlet by CASH, CARD or TRANSFER. {@code cancelled} counts orders cancelled that day.
 */
public record SalesSummary(LocalDate date, List<OutletSales> outlets, Totals totals) {

    public record TopItem(String name, int qty, BigDecimal sales) {
    }

    public record OutletSales(String outletId, String name, Outlet.Kind kind, int orders, BigDecimal sales, BigDecimal paidAtOutlet,
                              BigDecimal chargedToRooms, int cancelled, Map<String, BigDecimal> byMethod, List<TopItem> topItems) {
    }

    public record Totals(int orders, BigDecimal sales, BigDecimal paidAtOutlet, BigDecimal chargedToRooms, int cancelled, Map<String, BigDecimal> byMethod) {
    }

    private static final int TOP = 5;

    public static Map<String, BigDecimal> emptyMethods() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("CASH", Money.of(BigDecimal.ZERO));
        m.put("CARD", Money.of(BigDecimal.ZERO));
        m.put("TRANSFER", Money.of(BigDecimal.ZERO));
        return m;
    }

    /** {@code settled} are the day's settled orders (cancelled ones are ignored), {@code cancelled} the day's cancelled orders. */
    public static SalesSummary build(LocalDate date, List<Outlet> outlets, List<Order> settled, List<Order> cancelled, List<OrderLine> lines,
                                     List<OrderPayment> payments) {
        Map<String, List<OrderLine>> linesByOrder = lines.stream().collect(Collectors.groupingBy(OrderLine::getOrderId));
        Map<String, List<OrderPayment>> paymentsByOrder = payments.stream().collect(Collectors.groupingBy(OrderPayment::getOrderId));
        List<OutletSales> rows = new ArrayList<>();
        int tOrders = 0, tCancelled = 0;
        BigDecimal tSales = Money.of(BigDecimal.ZERO), tPaid = tSales, tRooms = tSales;
        Map<String, BigDecimal> tMethods = emptyMethods();
        for (Outlet outlet : outlets) {
            int count = 0;
            BigDecimal sales = Money.of(BigDecimal.ZERO), paid = sales, rooms = sales;
            Map<String, BigDecimal> methods = emptyMethods();
            Map<String, int[]> qty = new LinkedHashMap<>();
            Map<String, BigDecimal> itemSales = new LinkedHashMap<>();
            for (Order o : settled) {
                if (!outlet.getId().equals(o.getOutletId()) || o.getStatus() == Order.Status.CANCELLED) {
                    continue;
                }
                count++;
                sales = sales.add(o.getTotal());
                if ("ROOM".equals(o.getSettledBy())) {
                    rooms = rooms.add(o.getTotal());
                } else if ("PAYMENT".equals(o.getSettledBy())) {
                    paid = paid.add(o.getTotal());
                }
                for (OrderPayment p : paymentsByOrder.getOrDefault(o.getId(), List.of())) {
                    methods.merge(p.getMethod(), Money.of(p.getAmount()), BigDecimal::add);
                }
                for (OrderLine l : linesByOrder.getOrDefault(o.getId(), List.of())) {
                    if (l.isLive()) {
                        qty.computeIfAbsent(l.getName(), k -> new int[1])[0] += l.getQuantity();
                        itemSales.merge(l.getName(), Money.of(l.lineTotal().subtract(l.getDiscount() == null ? BigDecimal.ZERO : l.getDiscount())), BigDecimal::add);
                    }
                }
            }
            int cancelledCount = (int) cancelled.stream().filter(o -> outlet.getId().equals(o.getOutletId())).count();
            List<TopItem> top = qty.entrySet().stream().map(e -> new TopItem(e.getKey(), e.getValue()[0], itemSales.get(e.getKey())))
                    .sorted(Comparator.comparingInt(TopItem::qty).reversed().thenComparing(Comparator.comparing(TopItem::sales).reversed())
                            .thenComparing(TopItem::name))
                    .limit(TOP).toList();
            rows.add(new OutletSales(outlet.getId(), outlet.getName(), outlet.getKind(), count, sales, paid, rooms, cancelledCount, methods, top));
            tOrders += count;
            tCancelled += cancelledCount;
            tSales = tSales.add(sales);
            tPaid = tPaid.add(paid);
            tRooms = tRooms.add(rooms);
            for (Map.Entry<String, BigDecimal> e : methods.entrySet()) {
                tMethods.merge(e.getKey(), e.getValue(), BigDecimal::add);
            }
        }
        return new SalesSummary(date, rows, new Totals(tOrders, tSales, tPaid, tRooms, tCancelled, tMethods));
    }
}
