package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.CancelRequest;
import com.hospio.fnb.dto.OrderRequest;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.service.OrderService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kitchen 1.x (orders board, tickets, charge to a room) and Bar 1.x-2.x (sales, tabs). The kitchen and restaurant outlets are worked
 * by KITCHEN, the bar by BAR; front office takes room-service orders and charges rooms; managers do everything. The guards say who may
 * try; the service then checks the order's own outlet.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    static final String READ = "@access.has('KITCHEN') or @access.has('BAR') or @access.has('FRONT_OFFICE') or @access.managerView() or @access.has('FINANCE')"
            + " or @access.has('WAITER') or @access.has('CASHIER')";
    static final String TAKE = "@access.atLeast('KITCHEN','EDIT') or @access.atLeast('BAR','EDIT') or @access.atLeast('FRONT_OFFICE','EDIT')"
            + " or @access.atLeast('WAITER','EDIT')";
    /** The Hospio360 invoice restriction (docs/plan-monthly-invoices.md): new sales and bookings pause while it is on. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hospio.common.billing.BillingHold billingHold;

    private void notHeld(String what) {
        if (billingHold != null) {
            billingHold.require(what);
        }
    }

    static final String WORK = "@access.atLeast('KITCHEN','EDIT') or @access.atLeast('BAR','EDIT')";
    /** Serving and moving a table: the outlet's own people and waiters (the service checks which order and which step). */
    static final String SERVE = WORK + " or @access.atLeast('WAITER','EDIT')";
    static final String CHARGE = TAKE + " or @access.atLeast('CASHIER','EDIT')";

    private final OrderService service;

    /** The open board; {@code status=CLOSED} (or ALL) with {@code date} is that day's closed orders, {@code CANCELLED} that day's cancelled ones. */
    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<OrderResponse>> board(@RequestParam(required = false) String outletId, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.board(outletId, status, date));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<OrderResponse> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize(TAKE)
    public ApiResponse<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
        notHeld("New sales and tabs");
        return ApiResponse.ok("Order started", service.create(request));
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize(TAKE)
    public ApiResponse<OrderResponse> addLine(@PathVariable String id, @Valid @RequestBody OrderRequest.LineRequest line) {
        notHeld("New sales and tabs");
        return ApiResponse.ok("Added", service.addLine(id, line));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize(TAKE)
    public ApiResponse<OrderResponse> send(@PathVariable String id) {
        return ApiResponse.ok("Ticket sent", service.send(id));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize(SERVE)
    public ApiResponse<OrderResponse> status(@PathVariable String id, @RequestParam Order.Status status) {
        return ApiResponse.ok("Updated", service.setStatus(id, status));
    }

    /** One dish or drink ready or served: {@code status=READY|SERVED}. */
    @PutMapping("/{id}/lines/{lineId}/status")
    @PreAuthorize(SERVE)
    public ApiResponse<OrderResponse> lineStatus(@PathVariable String id, @PathVariable String lineId, @RequestParam OrderLine.Status status) {
        return ApiResponse.ok("Updated", service.setLineStatus(id, lineId, status));
    }

    /** A station (the kitchen) marks every line it makes on another outlet's order ready. */
    @PostMapping("/{id}/stations/{stationId}/ready")
    @PreAuthorize(WORK)
    public ApiResponse<OrderResponse> stationReady(@PathVariable String id, @PathVariable String stationId) {
        return ApiResponse.ok("Ready", service.stationReady(id, stationId));
    }

    @PostMapping("/{id}/lines/{lineId}/cancel")
    @PreAuthorize(TAKE)
    public ApiResponse<OrderResponse> cancelLine(@PathVariable String id, @PathVariable String lineId, @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok("Line cancelled", service.cancelLine(id, lineId, request.reason()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(TAKE)
    public ApiResponse<OrderResponse> cancel(@PathVariable String id, @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok("Order cancelled", service.cancel(id, request.reason()));
    }

    @PostMapping("/{id}/charge-to-room")
    @PreAuthorize(CHARGE)
    public ApiResponse<OrderResponse> chargeToRoom(@PathVariable String id, @RequestParam(required = false) String roomId) {
        return ApiResponse.ok("Charged to the room", service.chargeToRoom(id, roomId));
    }

    @PostMapping("/{id}/move-table")
    @PreAuthorize(SERVE)
    public ApiResponse<OrderResponse> moveTable(@PathVariable String id, @RequestParam String tableId) {
        return ApiResponse.ok("Tab moved", service.moveTable(id, tableId));
    }
}
