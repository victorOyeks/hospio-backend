package com.hospio.reservation.controller;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.ApiResponse;
import com.hospio.reservation.dto.AvailabilityResponse;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.dto.CancelBookingRequest;
import com.hospio.reservation.dto.ChangeBookingRequest;
import com.hospio.reservation.dto.CheckInRequest;
import com.hospio.reservation.dto.CheckOutRequest;
import com.hospio.reservation.dto.CreateBookingRequest;
import com.hospio.reservation.dto.TodayResponse;
import com.hospio.reservation.service.BookingService;
import com.hospio.reservation.service.ReservationListService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
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

/** Front office 1.1 and 2.x; Manager · Bookings. */
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;
    private final ReservationListService lists;
    private final com.hospio.reservation.service.GuestNotices notices;

    @GetMapping("/today")
    public ApiResponse<TodayResponse> today() {
        return ApiResponse.ok(service.today());
    }

    /**
     * Without {@code page} or {@code size}: the hotel's stays touching {@code from..to} (7 days back to 30 ahead), as before, for scripts and
     * other callers. With either: one page (docs/pagination.md), newest stay first, filtered by {@code q, status (comma separated), view
     * (arrivals, departures, in-house, upcoming, past, all), from/to (stays touching those days), arrivingFrom/arrivingTo (check-in day),
     * guestId} and, for the owner without X-Hotel-Id, {@code hotelId}; with the view counts and the money over the scope.
     */
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort,
                               @RequestParam(required = false) String q, @RequestParam(required = false) String view,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate arrivingFrom,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate arrivingTo,
                               @RequestParam(required = false) String hotelId, @RequestParam(required = false) String guestId) {
        if (!Paging.requested(page, size)) {
            String checked = ReservationListService.statuses(status);
            BookingStatus one = checked == null ? null : BookingStatus.valueOf(checked.split(",")[0]);
            return ApiResponse.ok(service.list(from, to, one));
        }
        return ApiResponse.ok(lists.bookings(new ReservationListService.BookingParams(page, size, sort, q, status, view, from, to, arrivingFrom,
                arrivingTo, hotelId, guestId)));
    }

    @GetMapping("/availability")
    public ApiResponse<AvailabilityResponse> availability(@RequestParam String roomTypeId,
                                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.availability(roomTypeId, from, to));
    }

    @GetMapping("/{id}")
    public ApiResponse<BookingResponse> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request) {
        BookingResponse created = service.create(request);
        return ApiResponse.ok("Booking created", created.withDeliveries(notices.confirmed(created)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<BookingResponse> change(@PathVariable String id, @RequestBody ChangeBookingRequest request) {
        BookingResponse before = service.get(id);
        BookingResponse after = service.change(id, request);
        return ApiResponse.ok("Booking changed", after.withDeliveries(notices.datesChanged(before, after)));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<BookingResponse> checkIn(@PathVariable String id, @RequestBody(required = false) CheckInRequest request) {
        return ApiResponse.ok("Checked in", service.checkIn(id, request == null ? new CheckInRequest(null, null, null, null, null, null, null) : request));
    }

    @PostMapping("/{id}/check-out")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<BookingResponse> checkOut(@PathVariable String id, @RequestBody(required = false) CheckOutRequest request) {
        return ApiResponse.ok("Checked out", service.checkOut(id, request == null ? new CheckOutRequest(null, null, null) : request));
    }

    @PostMapping("/{id}/late-check-out")
    @PreAuthorize("@access.manager()")
    public ApiResponse<BookingResponse> lateCheckOut(@PathVariable String id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime until) {
        return ApiResponse.ok("Late check-out set", service.lateCheckOut(id, until));
    }

    /** Cancelling needs Full access. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','FULL')")
    public ApiResponse<BookingResponse> cancel(@PathVariable String id, @Valid @RequestBody CancelBookingRequest request) {
        BookingResponse cancelled = service.cancel(id, request);
        return ApiResponse.ok("Booking cancelled", cancelled.withDeliveries(notices.cancelled(cancelled)));
    }
}
