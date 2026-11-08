package com.hospio.housekeeping.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.DamagedLinen;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LinenMovement;
import com.hospio.housekeeping.service.LaundryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Laundry 1.1-3.2. */
@RestController
@RequestMapping("/laundry")
@RequiredArgsConstructor
public class LaundryController {

    private final LaundryService service;

    @GetMapping("/orders")
    public ApiResponse<List<LaundryOrder>> jobs() {
        return ApiResponse.ok(service.jobs());
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<LaundryOrder> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/orders")
    public ApiResponse<LaundryOrder> create(@Valid @RequestBody TaskRequests.LaundryOrderRequest request) {
        return ApiResponse.ok("Order created", service.create(request));
    }

    @PutMapping("/orders/{id}/status")
    public ApiResponse<LaundryOrder> status(@PathVariable String id, @RequestParam LaundryOrder.Status status) {
        return ApiResponse.ok("Updated", service.setStatus(id, status));
    }

    @PostMapping("/orders/{id}/charge-to-room")
    public ApiResponse<LaundryOrder> chargeToRoom(@PathVariable String id) {
        return ApiResponse.ok("Charged to the room", service.chargeToRoom(id));
    }

    @PostMapping("/orders/{id}/paid")
    public ApiResponse<LaundryOrder> paid(@PathVariable String id, @RequestParam String paymentId) {
        return ApiResponse.ok("Paid", service.markPaid(id, paymentId));
    }

    @PostMapping("/orders/{id}/pocket-note")
    public ApiResponse<LaundryOrder> pocketNote(@PathVariable String id, @Valid @RequestBody TaskRequests.PocketNoteRequest request) {
        return ApiResponse.ok("Noted", service.pocketNote(id, request));
    }

    @GetMapping("/linen")
    public ApiResponse<List<LinenMovement>> linen() {
        return ApiResponse.ok(service.linenMovements());
    }

    @PostMapping("/linen")
    public ApiResponse<List<LinenMovement>> recordLinen(@Valid @RequestBody TaskRequests.LinenBatchRequest request) {
        return ApiResponse.ok("Recorded", service.recordLinen(request));
    }

    @GetMapping("/damaged")
    public ApiResponse<List<DamagedLinen>> damaged() {
        return ApiResponse.ok(service.damaged());
    }

    @PostMapping("/damaged")
    public ApiResponse<DamagedLinen> reportDamaged(@Valid @RequestBody TaskRequests.DamagedLinenRequest request) {
        return ApiResponse.ok("Recorded", service.reportDamaged(request));
    }

    @PostMapping("/damaged/{id}/ask-write-off")
    public ApiResponse<DamagedLinen> askToWriteOff(@PathVariable String id) {
        return ApiResponse.ok("Sent for approval", service.askToWriteOff(id));
    }

    @PostMapping("/damaged/{id}/approve-write-off")
    @PreAuthorize("@access.manager()")
    public ApiResponse<DamagedLinen> approveWriteOff(@PathVariable String id) {
        return ApiResponse.ok("Written off", service.approveWriteOff(id));
    }
}
