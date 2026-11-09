package com.hospio.folio.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.folio.dto.FolioResponse;
import com.hospio.folio.dto.HotelOverviewResponse;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.service.AdminOverviewService;
import com.hospio.folio.service.FolioService;
import com.hospio.folio.service.OrganizationService;
import io.swagger.v3.oas.annotations.Hidden;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Balances and organizations for reservation, payment and finance. Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalFolioController {

    private final FolioService folios;
    private final OrganizationService organizations;
    private final AdminOverviewService admin;

    /** platform-service business detail, Hotels tab: open folios and their balance. */
    @GetMapping("/admin/hotel-overview")
    public ApiResponse<HotelOverviewResponse> hotelOverview(@RequestParam String hotelId) {
        return ApiResponse.ok(admin.hotelOverview(hotelId));
    }

    /** finance's night audit: every open folio of the hotel with money still owing. */
    @GetMapping("/folios/open-balances")
    public ApiResponse<java.util.List<FolioService.OpenBalance>> openBalances(@RequestParam String hotelId) {
        return ApiResponse.ok(folios.openBalances(hotelId));
    }

    /** fnb's charge to room: 200 when the booking has an open folio, 404 when it hasn't. */
    @GetMapping("/folios/open")
    public ApiResponse<FolioService.OpenFolio> open(@RequestParam String bookingId) {
        return ApiResponse.ok(folios.openForBooking(bookingId));
    }

    @GetMapping("/folios/by-booking/{bookingId}")
    public ApiResponse<FolioResponse> byBooking(@PathVariable String bookingId) {
        return ApiResponse.ok(folios.byBooking(bookingId));
    }

    @GetMapping("/folios/by-booking/{bookingId}/balance")
    public ApiResponse<BigDecimal> balance(@PathVariable String bookingId) {
        return ApiResponse.ok(folios.byBooking(bookingId).balance());
    }

    @GetMapping("/folios/{id}")
    public ApiResponse<FolioResponse> get(@PathVariable String id) {
        return ApiResponse.ok(folios.get(id));
    }

    @GetMapping("/folios/{id}/stay-summary")
    public ApiResponse<String> staySummary(@PathVariable String id) {
        return ApiResponse.ok(folios.staySummary(id));
    }

    @GetMapping("/organizations/{id}")
    public ApiResponse<Organization> organization(@PathVariable String id) {
        return ApiResponse.ok(organizations.get(id));
    }
}
