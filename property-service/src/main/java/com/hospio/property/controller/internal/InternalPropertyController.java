package com.hospio.property.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.property.dto.OutOfOrderRequest;
import com.hospio.property.dto.RoomResponse;
import com.hospio.property.dto.RoomTypeResponse;
import com.hospio.property.service.PropertyService;
import io.swagger.v3.oas.annotations.Hidden;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rooms, types and prices for reservation, housekeeping, maintenance and fnb. Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPropertyController {

    private final PropertyService service;

    @GetMapping("/rooms")
    public ApiResponse<List<RoomResponse>> rooms(@RequestParam String hotelId, @RequestParam(required = false) String roomTypeId) {
        return ApiResponse.ok(service.rooms(hotelId, roomTypeId));
    }

    @GetMapping("/rooms/{id}")
    public ApiResponse<RoomResponse> room(@PathVariable String id) {
        return ApiResponse.ok(service.room(id));
    }

    @GetMapping("/room-types/{id}")
    public ApiResponse<RoomTypeResponse> roomType(@PathVariable String id) {
        return ApiResponse.ok(service.roomType(id));
    }

    @GetMapping("/room-types/{id}/price")
    public ApiResponse<BigDecimal> price(@PathVariable String id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.priceFor(id, date));
    }

    /** maintenance-service takes a room out of order from a repair. */
    @PostMapping("/rooms/{id}/out-of-order")
    public ApiResponse<RoomResponse> outOfOrder(@PathVariable String id, @RequestBody OutOfOrderRequest request) {
        return ApiResponse.ok(service.outOfOrder(id, request));
    }

    @PostMapping("/rooms/{id}/back-in-service")
    public ApiResponse<RoomResponse> backInService(@PathVariable String id) {
        return ApiResponse.ok(service.backInService(id));
    }
}
