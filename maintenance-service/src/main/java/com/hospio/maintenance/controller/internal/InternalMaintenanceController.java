package com.hospio.maintenance.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.maintenance.dto.MaintenanceRequests;
import com.hospio.maintenance.dto.MaintenanceViews;
import com.hospio.maintenance.service.MaintenanceService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Housekeeping opens a work order from a reported problem through here. Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMaintenanceController {

    private final MaintenanceService service;

    /** {@code outOfOrder=true}: the room goes out of order with it (refused 400 while a guest is checked in, nothing opened). */
    @PostMapping("/repairs")
    public ApiResponse<MaintenanceViews.RepairView> open(@Valid @RequestBody MaintenanceRequests.RepairRequest request,
                                                         @RequestParam(defaultValue = "false") boolean outOfOrder) {
        return ApiResponse.ok("Work order opened", service.openReported(request, outOfOrder));
    }
}
