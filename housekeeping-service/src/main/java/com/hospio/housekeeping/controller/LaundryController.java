package com.hospio.housekeeping.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.housekeeping.dto.LaundryDtos;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.service.LaundryService;
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

/** Laundry 1.x-3.x: guest laundry, the price list and hotel linen batches (docs/laundry-maintenance.md). */
@RestController
@RequestMapping("/laundry")
@RequiredArgsConstructor
public class LaundryController {

    static final String ORDERS_READ = "@access.managerView() or @access.has('LAUNDRY') or @access.has('FRONT_OFFICE')";
    static final String ORDERS_WRITE = "@access.manager() or @access.atLeast('LAUNDRY','EDIT') or @access.atLeast('FRONT_OFFICE','EDIT')";
    static final String LINEN_READ = "@access.managerView() or @access.has('LAUNDRY') or @access.has('HOUSEKEEPING')";
    static final String LINEN_SEND = "@access.manager() or @access.atLeast('HOUSEKEEPING','EDIT')";
    static final String LINEN_LAUNDRY = "@access.manager() or @access.atLeast('LAUNDRY','EDIT')";
    static final String MONEY = "@access.managerView() or @access.has('FINANCE') or @access.atLeast('LAUNDRY','FULL')";
    static final String STAFF = "@access.managerView() or @access.has('LAUNDRY') or @access.has('FRONT_OFFICE') or @access.has('HOUSEKEEPING') "
            + "or @access.has('FINANCE')";

    private final LaundryService service;
    private final com.hospio.housekeeping.service.HousekeepingLists lists;

    @GetMapping("/prices")
    @PreAuthorize(STAFF)
    public ApiResponse<List<LaundryDtos.PriceView>> prices() {
        return ApiResponse.ok(service.prices());
    }

    @PostMapping("/prices")
    @PreAuthorize("@access.manager()")
    public ApiResponse<LaundryDtos.PriceView> addPrice(@RequestBody LaundryDtos.PriceRequest request) {
        return ApiResponse.ok("Price added", service.savePrice(null, request));
    }

    @PutMapping("/prices/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<LaundryDtos.PriceView> savePrice(@PathVariable String id, @RequestBody LaundryDtos.PriceRequest request) {
        return ApiResponse.ok("Saved", service.savePrice(id, request));
    }

    @GetMapping("/orders")
    @PreAuthorize(ORDERS_READ)
    public ApiResponse<?> orders(@RequestParam(required = false) String status, @RequestParam(required = false) String roomId,
                                 @RequestParam(required = false) String bookingId, @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from, @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                                 @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            // Unpaged: the open board (bounded by nature) and internal callers; "all" returns every order, no cap.
            return ApiResponse.ok(service.orders(status));
        }
        return ApiResponse.ok(lists.laundryOrders(roomId, bookingId, new com.hospio.housekeeping.service.HousekeepingLists.Query(q, status, from, to, sort, page, size)));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize(ORDERS_READ)
    public ApiResponse<LaundryDtos.OrderView> get(@PathVariable String id) {
        return ApiResponse.ok(service.order(id));
    }

    @PostMapping("/orders")
    @PreAuthorize(ORDERS_WRITE)
    public ApiResponse<LaundryDtos.OrderView> create(@RequestBody LaundryDtos.OrderRequest request) {
        return ApiResponse.ok("Order taken", service.create(request));
    }

    @PostMapping("/orders/{id}/status")
    @PreAuthorize(ORDERS_WRITE)
    public ApiResponse<LaundryDtos.OrderView> status(@PathVariable String id, @RequestBody(required = false) LaundryDtos.StatusRequest request) {
        return ApiResponse.ok("Updated", service.move(id, request));
    }

    @PostMapping("/orders/{id}/charge-to-room")
    @PreAuthorize(ORDERS_WRITE)
    public ApiResponse<LaundryDtos.OrderView> chargeToRoom(@PathVariable String id) {
        return ApiResponse.ok("Charged to the room", service.chargeToRoom(id));
    }

    @PostMapping("/orders/{id}/paid")
    @PreAuthorize(ORDERS_WRITE)
    public ApiResponse<LaundryDtos.OrderView> paid(@PathVariable String id, @RequestBody(required = false) LaundryDtos.PaidRequest request) {
        return ApiResponse.ok("Paid", service.paid(id, request));
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize(ORDERS_WRITE)
    public ApiResponse<LaundryDtos.OrderView> cancel(@PathVariable String id, @RequestBody(required = false) LaundryDtos.ReasonRequest request) {
        return ApiResponse.ok("Cancelled", service.cancel(id, request));
    }

    @PostMapping("/orders/{id}/pocket-note")
    @PreAuthorize(ORDERS_WRITE)
    public ApiResponse<LaundryDtos.OrderView> pocketNote(@PathVariable String id, @Valid @RequestBody TaskRequests.PocketNoteRequest request) {
        return ApiResponse.ok("Noted", service.pocketNote(id, request));
    }

    @GetMapping("/linen-batches")
    @PreAuthorize(LINEN_READ)
    public ApiResponse<?> batches(@RequestParam(required = false) String status, @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from, @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                                 @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            return ApiResponse.ok(service.batches(status));
        }
        return ApiResponse.ok(lists.linenBatches(new com.hospio.housekeeping.service.HousekeepingLists.Query(q, status, from, to, sort, page, size)));
    }

    @PostMapping("/linen-batches")
    @PreAuthorize(LINEN_SEND)
    public ApiResponse<LaundryDtos.BatchView> send(@RequestBody LaundryDtos.BatchRequest request) {
        return ApiResponse.ok("Sent to laundry", service.send(request));
    }

    @PostMapping("/linen-batches/{id}/receive")
    @PreAuthorize(LINEN_LAUNDRY)
    public ApiResponse<LaundryDtos.BatchView> receive(@PathVariable String id, @RequestBody(required = false) LaundryDtos.BatchRequest request) {
        return ApiResponse.ok("Received", service.receive(id, request));
    }

    @PostMapping("/linen-batches/{id}/return")
    @PreAuthorize(LINEN_LAUNDRY)
    public ApiResponse<LaundryDtos.BatchView> giveBack(@PathVariable String id, @RequestBody LaundryDtos.ReturnRequest request) {
        return ApiResponse.ok("Returned", service.giveBack(id, request));
    }

    @PostMapping("/linen-batches/{id}/close")
    @PreAuthorize(LINEN_LAUNDRY)
    public ApiResponse<LaundryDtos.BatchView> close(@PathVariable String id, @RequestBody(required = false) LaundryDtos.ReasonRequest request) {
        return ApiResponse.ok("Closed", service.close(id, request));
    }

    @GetMapping("/summary")
    @PreAuthorize(MONEY)
    public ApiResponse<LaundryDtos.Summary> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.summary(from, to));
    }
}
