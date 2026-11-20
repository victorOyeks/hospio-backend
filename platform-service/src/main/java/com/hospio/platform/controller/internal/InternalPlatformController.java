package com.hospio.platform.controller.internal;

import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import com.hospio.platform.service.IntegrationSettingsService;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.FeeSettings;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPlatformController {

    private final ApprovalRepository approvals;
    private final PlatformSettingsService settings;
    private final IntegrationSettingsService integrations;
    private final com.hospio.platform.service.MarketerService marketers;
    private final com.hospio.platform.service.ChangeService changes;

    /** tenant-service sign-up with a referral code: the marketer it belongs to and whether they can refer; 404 when nobody has it. */
    @GetMapping("/marketers/referral-codes/{code}")
    public ApiResponse<com.hospio.platform.service.MarketerService.ReferralCodeInfo> referralCode(@PathVariable String code) {
        return ApiResponse.ok(marketers.referralCode(code).orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("No marketer has that referral code")));
    }

    /** finance's today screen: how many approvals are waiting at a hotel. */
    @GetMapping("/approvals/pending-count")
    public ApiResponse<Long> pendingCount(@org.springframework.web.bind.annotation.RequestParam String hotelId) {
        return ApiResponse.ok(approvals.countByHotelIdAndStatus(hotelId, com.hospio.common.enums.ApprovalStatus.PENDING));
    }

    @GetMapping("/approvals/{id}")
    public ApiResponse<Approval> approval(@PathVariable String id) {
        return ApiResponse.ok(approvals.findById(id).orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Approval not found")));
    }

    /**
     * The integration settings saved in the admin portal for one provider, decrypted, for the service that uses them (messaging-service,
     * payment-service). Internal API key only; null data when nothing is saved (the caller then uses its environment).
     */
    @GetMapping("/integrations/{provider}")
    public ApiResponse<IntegrationSettings> integration(@PathVariable String provider) {
        return ApiResponse.ok(integrations.saved(provider));
    }

    /** Fee lock: the platform default fee every business pays (model and share of daily sales); tenant-service checks fees against it. */
    @GetMapping("/fees/default")
    public ApiResponse<FeeDefault> feeDefault() {
        FeeSettings fees = settings.fees();
        return ApiResponse.ok(new FeeDefault(com.hospio.common.billing.FeeLock.DEFAULT_MODEL, fees.revenuePct()));
    }

    public record FeeDefault(String model, java.math.BigDecimal revenuePct) {
    }

    /**
     * Fee lock: tenant-service records a business's fee set at another rate (with its addendum reference) or put back to the default,
     * made by the platform admin named here, in the change log (area "Fee").
     */
    @org.springframework.web.bind.annotation.PostMapping("/changes/fee")
    public ApiResponse<String> feeChange(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody FeeChange request) {
        java.math.BigDecimal defaultPct = request.defaultPct() != null ? request.defaultPct() : settings.fees().revenuePct();
        return ApiResponse.ok(changes.recordFee(request.businessId(), request.feeValue(), request.feeAddendumRef(), defaultPct,
                request.previousValue(), request.actorId(), request.actorName()).getId());
    }

    public record FeeChange(@jakarta.validation.constraints.NotBlank String businessId, java.math.BigDecimal feeValue,
                            @jakarta.validation.constraints.Size(max = 120) String feeAddendumRef, java.math.BigDecimal previousValue,
                            java.math.BigDecimal defaultPct, String actorId, String actorName) {
    }

    /**
     * identity-service: the security group ("Require 2-step sign-in for every admin", session length), read with a short cache to
     * enforce them on platform staff sign-ins (docs/sign-in.md).
     */
    @GetMapping("/settings/security")
    public ApiResponse<com.hospio.platform.settings.SecuritySettings> security() {
        return ApiResponse.ok(settings.securityGroup());
    }

    /** payment-service settlements: provider fee % and Hospio360 share %, with the rest of the fees group. */
    @GetMapping("/settings/fees")
    public ApiResponse<FeeSettings> fees() {
        return ApiResponse.ok(settings.feesGroup());
    }
}
