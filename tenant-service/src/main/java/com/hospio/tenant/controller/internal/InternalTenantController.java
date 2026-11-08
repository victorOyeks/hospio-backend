package com.hospio.tenant.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.BusinessSummary;
import com.hospio.tenant.dto.BusinessesSummaryResponse;
import com.hospio.tenant.dto.FeeRequest;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.dto.InternalCreateBusinessRequest;
import com.hospio.tenant.dto.OnboardingResponse;
import com.hospio.tenant.entity.OnboardingDraft;
import com.hospio.tenant.entity.SetupTemplate;
import com.hospio.tenant.service.BusinessService;
import com.hospio.tenant.service.OnboardingService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-service hotel and business lookups, sign-up's business creation, templates, and what platform-service
 * needs for the admin dashboard, fees and the marketer portal. Service-to-service only (internal API key). The
 * platform reads run as the system so they answer the same whoever's token was forwarded (a marketer's, say).
 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalTenantController {

    private final BusinessService service;
    private final OnboardingService onboarding;

    @GetMapping("/hotels/{hotelId}")
    public ApiResponse<HotelResponse> hotel(@PathVariable String hotelId) {
        return ApiResponse.ok(service.hotel(hotelId));
    }

    @GetMapping("/businesses/{businessId}/hotels")
    public ApiResponse<List<HotelResponse>> hotels(@PathVariable String businessId) {
        return ApiResponse.ok(service.hotelsOf(businessId));
    }

    @GetMapping("/businesses/{businessId}")
    public ApiResponse<BusinessResponse> business(@PathVariable String businessId) {
        return ApiResponse.ok(service.get(businessId));
    }

    /** platform-service dashboard: businesses in total and by stage, hotels and rooms. */
    @GetMapping("/businesses/summary")
    public ApiResponse<BusinessesSummaryResponse> summary() {
        return ApiResponse.ok(asSystem(service::summary));
    }

    /** platform-service marketer portal: the businesses a marketer profile referred. */
    @GetMapping("/businesses")
    public ApiResponse<List<BusinessSummary>> byMarketer(@RequestParam String marketerId) {
        return ApiResponse.ok(asSystem(() -> service.byMarketer(marketerId)));
    }

    /** identity-service sign-up: the business with a preset id, source SELF_SERVE. */
    @PostMapping("/businesses")
    public ApiResponse<BusinessResponse> createBusiness(@Valid @RequestBody InternalCreateBusinessRequest request) {
        return ApiResponse.ok("Business created", service.createInternal(request));
    }

    /** platform-service keeps the business's fee in step with the fee rule it saved. */
    @PutMapping("/businesses/{businessId}/fee")
    public ApiResponse<BusinessResponse> fee(@PathVariable String businessId, @Valid @RequestBody FeeRequest request) {
        return ApiResponse.ok("Fee updated", asSystem(() -> service.setFee(businessId, request.feeModel(), request.feeValue())));
    }

    /** platform-service alerts: every onboarding draft, or those in one status, to spot stalled ones. */
    @GetMapping("/onboarding")
    public ApiResponse<List<OnboardingResponse>> onboarding(@RequestParam(required = false) OnboardingDraft.Status status) {
        return ApiResponse.ok(asSystem(() -> onboarding.listInternal(status)));
    }

    /** hotel-config applies a template by id. */
    @GetMapping("/setup-templates/{id}")
    public ApiResponse<SetupTemplate> template(@PathVariable String id) {
        return ApiResponse.ok(onboarding.template(id));
    }

    /** property-service keeps the room count current. */
    @PutMapping("/hotels/{hotelId}/room-count")
    public ApiResponse<Void> roomCount(@PathVariable String hotelId, @RequestParam int count) {
        service.setRoomCount(hotelId, count);
        return ApiResponse.ok("Updated", null);
    }

    private static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("tenant-internal"), work);
    }
}
