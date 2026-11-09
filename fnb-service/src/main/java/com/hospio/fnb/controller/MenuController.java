package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.CategoryRequest;
import com.hospio.fnb.dto.HappyHourRequest;
import com.hospio.fnb.dto.MenuItemRequest;
import com.hospio.fnb.dto.OutletRequest;
import com.hospio.fnb.dto.PriceChangeRequestDto;
import com.hospio.fnb.dto.TableRequest;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.HappyHour;
import com.hospio.fnb.entity.MenuCategory;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.service.MenuService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    static final String READ = OrderController.READ;
    static final String WORK = OrderController.WORK;
    /** Manager, or Full on kitchen or bar; the service narrows it to the outlet's own role. */
    static final String MENU = "@access.manager() or @access.atLeast('KITCHEN','FULL') or @access.atLeast('BAR','FULL')";

    private final MenuService service;

    @GetMapping("/outlets")
    @PreAuthorize(READ)
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
    @PreAuthorize(READ)
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

    /** The menu staff sell from; {@code all=true} (manager or Full) adds inactive items for the manage-menu screen. */
    @GetMapping("/outlets/{outletId}/menu")
    @PreAuthorize(READ)
    public ApiResponse<List<MenuItem>> menu(@PathVariable String outletId, @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.ok(service.menu(outletId, all));
    }

    @GetMapping("/outlets/{outletId}/categories")
    @PreAuthorize(READ)
    public ApiResponse<List<MenuCategory>> categories(@PathVariable String outletId) {
        return ApiResponse.ok(service.categories(outletId));
    }

    @PostMapping("/outlets/{outletId}/categories")
    @PreAuthorize(MENU)
    public ApiResponse<MenuCategory> addCategory(@PathVariable String outletId, @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok("Category added", service.saveCategory(outletId, null, request));
    }

    @PutMapping("/outlets/{outletId}/categories/{id}")
    @PreAuthorize(MENU)
    public ApiResponse<MenuCategory> updateCategory(@PathVariable String outletId, @PathVariable String id, @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok("Saved", service.saveCategory(outletId, id, request));
    }

    @PostMapping("/outlets/{outletId}/menu")
    @PreAuthorize(MENU)
    public ApiResponse<MenuItem> addItem(@PathVariable String outletId, @Valid @RequestBody MenuItemRequest request) {
        return ApiResponse.ok("Item added", service.saveItem(outletId, null, request));
    }

    @PutMapping("/outlets/{outletId}/menu/{id}")
    @PreAuthorize(MENU)
    public ApiResponse<MenuItem> updateItem(@PathVariable String outletId, @PathVariable String id, @Valid @RequestBody MenuItemRequest request) {
        return ApiResponse.ok("Saved", service.saveItem(outletId, id, request));
    }

    @PutMapping("/menu/{id}/available")
    @PreAuthorize(WORK)
    public ApiResponse<MenuItem> available(@PathVariable String id, @RequestParam boolean available) {
        return ApiResponse.ok(available ? "Back on the menu" : "Marked sold out", service.setAvailable(id, available));
    }

    /** 200 with the item when the price changed now (Full or a manager); 202 {@code {status:"PENDING_APPROVAL", requestId}} when a manager has to approve it. */
    @PutMapping("/menu/{id}/price")
    @PreAuthorize(WORK)
    public ResponseEntity<ApiResponse<?>> changePrice(@PathVariable String id, @Valid @RequestBody PriceChangeRequestDto request) {
        MenuService.PriceChangeResult r = service.changePrice(id, request);
        if (r.pending() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok("Price change sent to the manager for approval",
                    Map.of("status", "PENDING_APPROVAL", "requestId", r.pending().getId())));
        }
        return ResponseEntity.ok(ApiResponse.ok("Price changed", r.item()));
    }

    @GetMapping("/outlets/{outletId}/happy-hours")
    @PreAuthorize(READ)
    public ApiResponse<List<HappyHour>> happyHours(@PathVariable String outletId) {
        return ApiResponse.ok(service.happyHours(outletId));
    }

    @PostMapping("/outlets/{outletId}/happy-hours")
    @PreAuthorize(MENU)
    public ApiResponse<HappyHour> addHappyHour(@PathVariable String outletId, @Valid @RequestBody HappyHourRequest request) {
        return ApiResponse.ok("Happy hour added", service.saveHappyHour(outletId, null, request));
    }

    @PutMapping("/outlets/{outletId}/happy-hours/{id}")
    @PreAuthorize(MENU)
    public ApiResponse<HappyHour> updateHappyHour(@PathVariable String outletId, @PathVariable String id, @Valid @RequestBody HappyHourRequest request) {
        return ApiResponse.ok("Saved", service.saveHappyHour(outletId, id, request));
    }
}
