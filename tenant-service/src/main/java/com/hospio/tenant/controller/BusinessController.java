package com.hospio.tenant.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.CreateBusinessRequest;
import com.hospio.tenant.dto.CreateHotelRequest;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.dto.StageChangeRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.service.BusinessService;
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

@RestController
@RequiredArgsConstructor
public class BusinessController {

    private final BusinessService service;

    /** Owner and manager portals: my business and its hotels. */
    @GetMapping("/businesses/mine")
    public ApiResponse<BusinessResponse> mine() {
        return ApiResponse.ok(service.mine());
    }

    @GetMapping("/hotels")
    public ApiResponse<List<HotelResponse>> myHotels() {
        return ApiResponse.ok(service.mine().hotels());
    }

    @PostMapping("/hotels")
    @PreAuthorize("@access.owner()")
    public ApiResponse<HotelResponse> addHotel(@Valid @RequestBody CreateHotelRequest request) {
        return ApiResponse.ok("Hotel added", service.addHotel(request));
    }

    /** Platform admin C1-C4, C10, C11. */
    @GetMapping("/businesses")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<BusinessResponse>> list(@RequestParam(required = false) Business.Stage stage) {
        return ApiResponse.ok(service.list(stage));
    }

    @GetMapping("/businesses/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/businesses")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> create(@Valid @RequestBody CreateBusinessRequest request) {
        return ApiResponse.ok("Business created", service.create(request));
    }

    @PutMapping("/businesses/{id}/stage")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> changeStage(@PathVariable String id, @Valid @RequestBody StageChangeRequest request) {
        return ApiResponse.ok("Stage changed", service.changeStage(id, request));
    }

    @PutMapping("/businesses/{id}/account-manager")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> accountManager(@PathVariable String id, @RequestParam String adminId) {
        return ApiResponse.ok("Account manager changed", service.assignAccountManager(id, adminId));
    }
}
