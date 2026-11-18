package com.hospio.inventory.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.inventory.service.DefaultStoresBackfill;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hospio360 platform maintenance on inventory (docs/inventory-stores.md). */
@RestController
@RequiredArgsConstructor
public class AdminInventoryController {

    private final DefaultStoresBackfill backfill;

    @Operation(summary = "SUPER_ADMIN: give every hotel with no store at all the default Main, Kitchen, Bar and Housekeeping stores "
            + "(the same as a new hotel gets); safe to run again, a hotel with any store is left alone")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/admin/stores/backfill-defaults")
    public ApiResponse<DefaultStoresBackfill.Result> backfillDefaultStores() {
        DefaultStoresBackfill.Result r = backfill.run();
        return ApiResponse.ok(r.hotelsChanged() + " hotels got the default stores", r);
    }
}
