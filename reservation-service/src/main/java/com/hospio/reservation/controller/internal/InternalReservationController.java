package com.hospio.reservation.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.service.BookingService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Who is in which room, for fnb (charge to room), housekeeping and finance (night audit). Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalReservationController {

    private final BookingService service;

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
}
