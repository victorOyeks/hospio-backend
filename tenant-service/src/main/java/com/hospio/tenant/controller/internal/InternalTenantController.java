package com.hospio.tenant.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.service.BusinessService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cross-service hotel and business lookups. Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalTenantController {

    private final BusinessService service;

    @GetMapping("/hotels/{hotelId}")
    public ApiResponse<HotelResponse> hotel(@PathVariable String hotelId) {
        return ApiResponse.ok(service.hotel(hotelId));
    }

    @GetMapping("/businesses/{businessId}/hotels")
    public ApiResponse<List<HotelResponse>> hotels(@PathVariable String businessId) {
        return ApiResponse.ok(service.hotelsOf(businessId));
    }

    @GetMapping("/businesses/{businessId}")
    public ApiResponse<BusinessResponse> business(@PathVariable String businessId) {
        return ApiResponse.ok(service.get(businessId));
    }

    /** property-service keeps the room count current. */
    @PutMapping("/hotels/{hotelId}/room-count")
    public ApiResponse<Void> roomCount(@PathVariable String hotelId, @RequestParam int count) {
        service.setRoomCount(hotelId, count);
        return ApiResponse.ok("Updated", null);
    }
}
