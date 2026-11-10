package com.hospio.property.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.property.dto.AreaRequest;
import com.hospio.property.dto.BulkRoomsRequest;
import com.hospio.property.dto.BulkRoomsResponse;
import com.hospio.property.dto.OutOfOrderRequest;
import com.hospio.property.dto.RoomRequest;
import com.hospio.property.dto.RoomResponse;
import com.hospio.property.dto.RoomTypeRequest;
import com.hospio.property.dto.RoomTypeResponse;
import com.hospio.property.dto.SpecialPriceRequest;
import com.hospio.property.entity.Area;
import com.hospio.property.service.PropertyService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manager · Rooms: board, details, types & prices, add room, special price, out of order. */
@RestController
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService service;

    @GetMapping("/room-types")
    public ApiResponse<List<RoomTypeResponse>> roomTypes() {
        return ApiResponse.ok(service.roomTypes(TenantContext.requireHotel()));
    }

    @PostMapping("/room-types")
    @PreAuthorize("@access.manager()")
    public ApiResponse<RoomTypeResponse> addRoomType(@Valid @RequestBody RoomTypeRequest request) {
        return ApiResponse.ok("Room type added", service.saveRoomType(null, request));
    }

    @PutMapping("/room-types/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<RoomTypeResponse> updateRoomType(@PathVariable String id, @Valid @RequestBody RoomTypeRequest request) {
        RoomTypeResponse saved = service.saveRoomType(id, request);
        return ApiResponse.ok(saved.priceSentToOwner() ? "Saved · the new price is waiting for the owner" : "Saved", saved);
    }

    @Operation(summary = "Hide a room type nobody uses; 409 while rooms still reference it")
    @DeleteMapping("/room-types/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<RoomTypeResponse> deleteRoomType(@PathVariable String id) {
        return ApiResponse.ok("Room type hidden", service.deactivateRoomType(id));
    }

    @PostMapping("/room-types/{id}/special-prices")
    @PreAuthorize("@access.manager()")
    public ApiResponse<RoomTypeResponse> addSpecialPrice(@PathVariable String id, @Valid @RequestBody SpecialPriceRequest request) {
        return ApiResponse.ok("Special price added", service.addSpecialPrice(id, request));
    }

    @GetMapping("/rooms")
    public ApiResponse<List<RoomResponse>> rooms(@RequestParam(required = false) String roomTypeId) {
        return ApiResponse.ok(service.rooms(TenantContext.requireHotel(), roomTypeId));
    }

    @GetMapping("/rooms/{id}")
    public ApiResponse<RoomResponse> room(@PathVariable String id) {
        return ApiResponse.ok(service.room(id));
    }

    @PostMapping("/rooms")
    @PreAuthorize("@access.manager()")
    public ApiResponse<RoomResponse> addRoom(@Valid @RequestBody RoomRequest request) {
        return ApiResponse.ok("Room added", service.saveRoom(null, request));
    }

    @Operation(summary = "Add up to 500 rooms at once to the hotel in X-Hotel-Id; numbers already present are skipped")
    @PostMapping("/rooms/bulk")
    @PreAuthorize("@access.manager()")
    public ApiResponse<BulkRoomsResponse> addRoomsBulk(@Valid @RequestBody BulkRoomsRequest request) {
        return ApiResponse.ok("Rooms added", service.bulkRooms(request));
    }

    @PutMapping("/rooms/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<RoomResponse> updateRoom(@PathVariable String id, @Valid @RequestBody RoomRequest request) {
        return ApiResponse.ok("Saved", service.saveRoom(id, request));
    }

    /** Maintenance and managers take a room out of order; housekeeping may not. */
    @PostMapping("/rooms/{id}/out-of-order")
    @PreAuthorize("@access.manager() or @access.atLeast('MAINTENANCE','EDIT')")
    public ApiResponse<RoomResponse> outOfOrder(@PathVariable String id, @Valid @RequestBody OutOfOrderRequest request) {
        return ApiResponse.ok("Room out of order", service.outOfOrder(id, request));
    }

    @PostMapping("/rooms/{id}/back-in-service")
    @PreAuthorize("@access.manager() or @access.atLeast('MAINTENANCE','EDIT')")
    public ApiResponse<RoomResponse> backInService(@PathVariable String id) {
        return ApiResponse.ok("Room back in service", service.backInService(id));
    }

    @GetMapping("/areas")
    public ApiResponse<List<Area>> areas() {
        return ApiResponse.ok(service.areas(TenantContext.requireHotel()));
    }

    @PostMapping("/areas")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Area> addArea(@Valid @RequestBody AreaRequest request) {
        return ApiResponse.ok("Area added", service.saveArea(null, request));
    }

    @PutMapping("/areas/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Area> updateArea(@PathVariable String id, @Valid @RequestBody AreaRequest request) {
        return ApiResponse.ok("Saved", service.saveArea(id, request));
    }
}
