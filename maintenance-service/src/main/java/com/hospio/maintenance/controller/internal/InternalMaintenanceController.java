package com.hospio.maintenance.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.maintenance.dto.MaintenanceRequests;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.service.MaintenanceService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Housekeeping and the front desk open repairs through here. Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMaintenanceController {

    private final MaintenanceService service;

    @PostMapping("/repairs")
    public ApiResponse<Repair> open(@Valid @RequestBody MaintenanceRequests.RepairRequest request) {
        return ApiResponse.ok("Repair opened", service.open(request));
    }
}
