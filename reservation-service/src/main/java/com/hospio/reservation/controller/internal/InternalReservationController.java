package com.hospio.reservation.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.reservation.dto.AdminNightsResponse;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.dto.DaySummary;
import com.hospio.reservation.dto.HotelOverviewResponse;
import com.hospio.reservation.service.AdminOverviewService;
import com.hospio.reservation.service.BookingService;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is in which room, for fnb (charge to room), housekeeping and finance (night audit), plus the platform
 * admin figures (fee statements, hotel overview). Service-to-service only.
 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalReservationController {

    private final BookingService service;
    private final AdminOverviewService admin;

    @GetMapping("/bookings/{id}")
    public ApiResponse<BookingResponse> booking(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/rooms/{roomId}/active-booking")
    public ApiResponse<BookingResponse> activeOnRoom(@PathVariable String roomId) {
        return ApiResponse.ok(service.activeOnRoom(roomId));
    }

    @GetMapping("/in-house")
    public ApiResponse<List<BookingResponse>> inHouse(@RequestParam String hotelId) {
        return ApiResponse.ok(service.inHouse(hotelId));
    }

    /** BOOKED arrivals on a date, optionally for one room: housekeeping marks "guest arrives" and finance lists who is still to come. */
    @GetMapping("/arrivals")
    public ApiResponse<List<BookingResponse>> arrivals(@RequestParam String hotelId,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                       @RequestParam(required = false) String roomId) {
        return ApiResponse.ok(service.bookedArrivals(hotelId, date, roomId));
    }

    /** Arrivals, departures, in-house, no-shows and rooms occupied for a business day (finance's night audit and today screen). */
    @GetMapping("/day-summary")
    public ApiResponse<DaySummary> daySummary(@RequestParam String hotelId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.daySummary(hotelId, date));
    }

    /** The night audit's markNoShows: every BOOKED arrival up to the date becomes NO_SHOW; answers how many. */
    @PostMapping("/arrivals/no-show")
    public ApiResponse<Integer> markNoShows(@RequestParam String hotelId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok("Marked no-show", service.markNoShows(hotelId, date));
    }

    /** platform-service fee statements: room nights and booking value of a business over [from, to]. */
    @GetMapping("/admin/nights")
    public ApiResponse<AdminNightsResponse> nights(@RequestParam String businessId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(admin.nights(businessId, from, to));
    }

    /** platform-service business detail, Hotels tab. */
    @GetMapping("/admin/hotel-overview")
    public ApiResponse<HotelOverviewResponse> hotelOverview(@RequestParam String hotelId) {
        return ApiResponse.ok(admin.hotelOverview(hotelId));
    }
}
