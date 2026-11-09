package com.hospio.inventory.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.inventory.dto.StockMoves;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalInventoryController {

    private final InventoryService service;

    @GetMapping("/levels")
    public ApiResponse<List<StockLevel>> levels(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(service.levels(storeId));
    }

    /** The hotel's active items (laundry reads its linen list from here). */
    @GetMapping("/items")
    public ApiResponse<List<com.hospio.inventory.dto.InventoryViews.ItemView>> items() {
        return ApiResponse.ok(service.items());
    }

    /** Maintenance parts, linen to and from the laundry, condemned linen: see docs/laundry-maintenance.md. */
    @PostMapping("/stock/moves")
    public ApiResponse<StockMoves.Result> move(@RequestBody StockMoves.Request request) {
        return ApiResponse.ok(service.stockMoves(request));
    }
}
