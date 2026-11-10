package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.identity.dto.SharedDeviceDtos;
import com.hospio.identity.service.SharedDeviceService;
import io.swagger.v3.oas.annotations.Operation;
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

/** Shared devices (docs/shared-devices.md): set up from the account menu, managed from Hotel setup. */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class SharedDeviceController {

    private final SharedDeviceService devices;

    @Operation(summary = "Use this device as a shared device: Full access at the hotel, a manager or the owner. The device token comes back once")
    @PostMapping
    public ApiResponse<SharedDeviceDtos.SetupResult> setup(@Valid @RequestBody SharedDeviceDtos.Setup request) {
        return ApiResponse.ok("Shared device set up", devices.setup(request));
    }

    @Operation(summary = "The hotel's shared devices (the X-Hotel-Id hotel when hotelId is not given): managers and the owner")
    @GetMapping
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<SharedDeviceDtos.Device>> list(@RequestParam(required = false) String hotelId) {
        return ApiResponse.ok(devices.list(hotelId));
    }

    @Operation(summary = "Rename a device, change its roles or auto-lock minutes (1-60)")
    @PutMapping("/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<SharedDeviceDtos.Device> change(@PathVariable String id, @Valid @RequestBody SharedDeviceDtos.Change request) {
        return ApiResponse.ok("Device saved", devices.change(id, request));
    }

    @Operation(summary = "Remove a device: its token stops working and the sessions opened on it end")
    @PostMapping("/{id}/revoke")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Void> revoke(@PathVariable String id) {
        devices.revoke(id);
        return ApiResponse.ok("Device removed", null);
    }
}
