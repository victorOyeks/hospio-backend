package com.hospio.reservation.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.dto.GuestRequest;
import com.hospio.reservation.entity.Guest;
import com.hospio.reservation.service.BookingService;
import com.hospio.reservation.service.GuestService;
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

/** Front office 5.1 · Find a guest; Manager and Owner · Guests. */
@RestController
@RequestMapping("/guests")
@RequiredArgsConstructor
public class GuestController {

    private final GuestService guests;
    private final BookingService bookings;

    @GetMapping
    public ApiResponse<List<Guest>> search(@RequestParam(required = false) String q) {
        return ApiResponse.ok(guests.search(q));
    }

    @GetMapping("/{id}")
    public ApiResponse<Guest> get(@PathVariable String id) {
        return ApiResponse.ok(guests.get(id));
    }

    @GetMapping("/{id}/stays")
    public ApiResponse<List<BookingResponse>> stays(@PathVariable String id) {
        return ApiResponse.ok(bookings.forGuest(id));
    }

    @PostMapping
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<Guest> create(@Valid @RequestBody GuestRequest request) {
        return ApiResponse.ok("Guest saved", guests.findOrCreate(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<Guest> update(@PathVariable String id, @Valid @RequestBody GuestRequest request) {
        return ApiResponse.ok("Guest saved", guests.update(id, request));
    }
}
