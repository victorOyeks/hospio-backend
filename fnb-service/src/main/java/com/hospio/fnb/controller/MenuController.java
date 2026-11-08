package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.HappyHourRequest;
import com.hospio.fnb.dto.MenuItemRequest;
import com.hospio.fnb.dto.OutletRequest;
import com.hospio.fnb.dto.PriceChangeRequestDto;
import com.hospio.fnb.dto.TableRequest;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.HappyHour;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.service.MenuService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Kitchen 2.x-3.x and Bar 3.x-4.x: menus, prices, sold out, happy hours; Hotel setup 3.x: outlets and tables. */
@RestController
@RequiredArgsConstructor
public class MenuController {

    private final MenuService service;

    @GetMapping("/outlets")
    public ApiResponse<List<Outlet>> outlets() {
        return ApiResponse.ok(service.outlets());
    }

    @PostMapping("/outlets")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Outlet> addOutlet(@Valid @RequestBody OutletRequest request) {
        return ApiResponse.ok("Outlet added", service.saveOutlet(null, request));
    }

    @PutMapping("/outlets/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Outlet> updateOutlet(@PathVariable String id, @Valid @RequestBody OutletRequest request) {
        return ApiResponse.ok("Saved", service.saveOutlet(id, request));
    }

    @GetMapping("/outlets/{outletId}/tables")
    public ApiResponse<List<DiningTable>> tables(@PathVariable String outletId) {
        return ApiResponse.ok(service.tables(outletId));
    }

    @PostMapping("/outlets/{outletId}/tables")
    @PreAuthorize("@access.manager()")
    public ApiResponse<DiningTable> addTable(@PathVariable String outletId, @Valid @RequestBody TableRequest request) {
        return ApiResponse.ok("Table added", service.saveTable(outletId, null, request));
    }

    @PutMapping("/outlets/{outletId}/tables/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<DiningTable> updateTable(@PathVariable String outletId, @PathVariable String id, @Valid @RequestBody TableRequest request) {
        return ApiResponse.ok("Saved", service.saveTable(outletId, id, request));
    }

    @GetMapping("/outlets/{outletId}/menu")
    public ApiResponse<List<MenuItem>> menu(@PathVariable String outletId) {
        return ApiResponse.ok(service.menu(outletId));
    }

    @PostMapping("/outlets/{outletId}/menu")
    public ApiResponse<MenuItem> addItem(@PathVariable String outletId, @Valid @RequestBody MenuItemRequest request) {
        return ApiResponse.ok("Item added", service.saveItem(outletId, null, request));
    }

    @PutMapping("/outlets/{outletId}/menu/{id}")
    public ApiResponse<MenuItem> updateItem(@PathVariable String outletId, @PathVariable String id, @Valid @RequestBody MenuItemRequest request) {
        return ApiResponse.ok("Saved", service.saveItem(outletId, id, request));
    }

    @PutMapping("/menu/{id}/available")
    public ApiResponse<MenuItem> available(@PathVariable String id, @RequestParam boolean available) {
        return ApiResponse.ok(available ? "Back on the menu" : "Marked sold out", service.setAvailable(id, available));
    }

    @PutMapping("/menu/{id}/price")
    public ApiResponse<MenuItem> changePrice(@PathVariable String id, @Valid @RequestBody PriceChangeRequestDto request) {
        return ApiResponse.ok("Price changed", service.changePrice(id, request));
    }

    @GetMapping("/outlets/{outletId}/happy-hours")
    public ApiResponse<List<HappyHour>> happyHours(@PathVariable String outletId) {
        return ApiResponse.ok(service.happyHours(outletId));
    }

    @PostMapping("/outlets/{outletId}/happy-hours")
    public ApiResponse<HappyHour> addHappyHour(@PathVariable String outletId, @Valid @RequestBody HappyHourRequest request) {
        return ApiResponse.ok("Happy hour added", service.saveHappyHour(outletId, null, request));
    }

    @PutMapping("/outlets/{outletId}/happy-hours/{id}")
    public ApiResponse<HappyHour> updateHappyHour(@PathVariable String outletId, @PathVariable String id, @Valid @RequestBody HappyHourRequest request) {
        return ApiResponse.ok("Saved", service.saveHappyHour(outletId, id, request));
    }
}
