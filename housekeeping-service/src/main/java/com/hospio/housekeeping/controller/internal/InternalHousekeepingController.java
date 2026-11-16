package com.hospio.housekeeping.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.housekeeping.entity.RoomStatus;
import com.hospio.housekeeping.service.HousekeepingService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cleanliness per room for the front desk room board. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalHousekeepingController {

    private final HousekeepingService service;
    private final com.hospio.housekeeping.service.SalesReport salesReport;

    /** platform-service's daily fee: this service's share of what the hotel sold that Lagos day (docs/plan-daily-fees.md). */
    @org.springframework.web.bind.annotation.GetMapping("/sales/daily")
    public com.hospio.common.response.ApiResponse<com.hospio.common.billing.DailySales> dailySales(
            @org.springframework.web.bind.annotation.RequestParam String hotelId,
            @org.springframework.web.bind.annotation.RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return com.hospio.common.response.ApiResponse.ok(salesReport.daily(hotelId, date));
    }

    @GetMapping("/room-status")
    public ApiResponse<List<RoomStatus>> roomBoard(@RequestParam String hotelId) {
        return ApiResponse.ok(service.roomBoard(hotelId));
    }
}
