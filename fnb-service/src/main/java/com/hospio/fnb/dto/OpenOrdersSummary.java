package com.hospio.fnb.dto;

import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.Outlet;
import java.math.BigDecimal;
import java.util.List;

/** Orders still open and not settled at each outlet: what the night audit makes staff settle or move before the day closes. */
public record OpenOrdersSummary(int openTabs, int unsettled, List<OutletOpen> outlets) {

    public record OpenOrder(String id, String number, Order.Kind kind, String tableNo, String roomNumber, BigDecimal total) {
    }

    public record OutletOpen(String outletId, String name, Outlet.Kind kind, int openTabs, int unsettled, List<OpenOrder> orders) {
    }
}
