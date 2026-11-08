package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.OrderRequest;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Kitchen 1.x (orders board, tickets, charge to a room) and Bar 1.x-2.x (sales, tabs). */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService service;

    @GetMapping
    public ApiResponse<List<OrderResponse>> board(@RequestParam(required = false) String outletId) {
        return ApiResponse.ok(service.board(outletId));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
        return ApiResponse.ok("Order started", service.create(request));
    }

    @PostMapping("/{id}/lines")
    public ApiResponse<OrderResponse> addLine(@PathVariable String id, @Valid @RequestBody OrderRequest.LineRequest line) {
        return ApiResponse.ok("Added", service.addLine(id, line));
    }

    @PostMapping("/{id}/send")
    public ApiResponse<OrderResponse> send(@PathVariable String id) {
        return ApiResponse.ok("Ticket sent", service.send(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<OrderResponse> status(@PathVariable String id, @RequestParam Order.Status status) {
        return ApiResponse.ok("Updated", service.setStatus(id, status));
    }

    @PostMapping("/{id}/charge-to-room")
    public ApiResponse<OrderResponse> chargeToRoom(@PathVariable String id, @RequestParam(required = false) String roomId) {
        return ApiResponse.ok("Charged to the room", service.chargeToRoom(id, roomId));
    }

    /** After payment-service approved a payment for this sale. */
    @PostMapping("/{id}/paid")
    public ApiResponse<OrderResponse> paid(@PathVariable String id, @RequestParam String paymentId) {
        return ApiResponse.ok("Paid", service.markPaid(id, paymentId));
    }

    @PostMapping("/{id}/move-table")
    public ApiResponse<OrderResponse> moveTable(@PathVariable String id, @RequestParam String tableId) {
        return ApiResponse.ok("Tab moved", service.moveTable(id, tableId));
    }
}
