package com.hospio.tenant.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.BusinessSummary;
import com.hospio.tenant.dto.CreateBusinessRequest;
import com.hospio.tenant.dto.CreateHotelRequest;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.dto.HotelStatusRequest;
import com.hospio.tenant.dto.StageChangeRequest;
import com.hospio.tenant.dto.UpdateBusinessRequest;
import com.hospio.tenant.dto.UpdateHotelRequest;
import com.hospio.tenant.dto.UpdateMyBusinessRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.service.BusinessService;
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

    @Operation(summary = "Owner: edit the business profile (name, legal, contacts, address, brand, timezone)")
    @PutMapping("/businesses/mine")
    @PreAuthorize("@access.owner()")
    public ApiResponse<BusinessResponse> updateMine(@Valid @RequestBody UpdateMyBusinessRequest request) {
        return ApiResponse.ok("Saved", service.updateMine(request));
    }

    @GetMapping("/hotels")
    public ApiResponse<List<HotelResponse>> myHotels() {
        return ApiResponse.ok(service.mine().hotels());
    }

    @Operation(summary = "One hotel: its owner, someone granted it, or the platform")
    @GetMapping("/hotels/{id}")
    public ApiResponse<HotelResponse> hotel(@PathVariable String id) {
        return ApiResponse.ok(service.hotelForCaller(id));
    }

    @Operation(summary = "Add a hotel to my business (owner) or to businessId (platform); publishes HotelCreated")
    @PostMapping("/hotels")
    @PreAuthorize("@access.owner()")
    public ApiResponse<HotelResponse> addHotel(@Valid @RequestBody CreateHotelRequest request) {
        return ApiResponse.ok("Hotel added", service.addHotel(request));
    }

    @Operation(summary = "Edit a hotel: owner, that hotel's manager, or platform")
    @PutMapping("/hotels/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<HotelResponse> updateHotel(@PathVariable String id, @Valid @RequestBody UpdateHotelRequest request) {
        return ApiResponse.ok("Saved", service.updateHotel(id, request));
    }

    @Operation(summary = "Set a hotel ACTIVE, PAUSED or CLOSED with a reason: owner or platform")
    @PutMapping("/hotels/{id}/status")
    @PreAuthorize("@access.owner()")
    public ApiResponse<HotelResponse> hotelStatus(@PathVariable String id, @Valid @RequestBody HotelStatusRequest request) {
        return ApiResponse.ok("Status changed", service.setHotelStatus(id, request));
    }

    /** Platform admin C1-C4, C10, C11. */
    @Operation(summary = "Platform: businesses, paged, filtered by stage and searched by name, owner or city")
    @GetMapping("/businesses")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PagedResponse<BusinessSummary>> list(@RequestParam(required = false) Business.Stage stage,
                                                             @RequestParam(required = false) String q,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(stage, q, page, size));
    }

    @GetMapping("/businesses/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(summary = "Platform: the hotels of a business")
    @GetMapping("/businesses/{id}/hotels")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<HotelResponse>> hotelsOf(@PathVariable String id) {
        service.get(id);
        return ApiResponse.ok(service.hotelsOf(id));
    }

    @PostMapping("/businesses")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> create(@Valid @RequestBody CreateBusinessRequest request) {
        return ApiResponse.ok("Business created", service.create(request));
    }

    @Operation(summary = "Platform: edit every business field (profile, owner contacts, fee, contract); null fields are left alone")
    @PutMapping("/businesses/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessResponse> update(@PathVariable String id, @Valid @RequestBody UpdateBusinessRequest request) {
        return ApiResponse.ok("Saved", service.update(id, request));
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
