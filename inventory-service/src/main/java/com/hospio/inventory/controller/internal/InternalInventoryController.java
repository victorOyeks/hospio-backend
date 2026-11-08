package com.hospio.inventory.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
