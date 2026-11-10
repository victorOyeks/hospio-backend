package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PageWithCounts;
import com.hospio.platform.dto.AdminResponses.CommissionRow;
import com.hospio.platform.dto.AdminResponses.MarketerDetail;
import com.hospio.platform.dto.AdminResponses.MarketerInvited;
import com.hospio.platform.dto.AdminResponses.MarketerRow;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.Referral;
import com.hospio.platform.service.MarketerService;
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

/** Marketers (E): profiles, referrals and commissions as the platform sees them. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminMarketerController {

    private final MarketerService marketers;

    @Operation(summary = "Marketer profiles with the number of businesses they brought live, what they earned and what is due")
    @GetMapping("/marketers")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<MarketerRow>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return ApiResponse.ok(marketers.profiles(status, q));
    }

    @Operation(summary = "Invite a marketer: creates the MARKETER login in identity and the profile; returns the login with its deliveries")
    @PostMapping("/marketers/invite")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MarketerInvited> invite(@Valid @RequestBody PlatformRequests.MarketerInviteRequest request) {
        return ApiResponse.ok("Marketer invited", marketers.invite(request));
    }

    @Operation(summary = "One marketer: profile, agreements per business, referrals and commissions with totals")
    @GetMapping("/marketers/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MarketerDetail> get(@PathVariable String id) {
        return ApiResponse.ok(marketers.detail(id));
    }

    @Operation(summary = "Change a marketer's share, months or status (ENDED closes their active agreements)")
    @PutMapping("/marketers/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MarketerProfile> update(@PathVariable String id, @RequestBody PlatformRequests.MarketerUpdateRequest request) {
        return ApiResponse.ok("Updated", marketers.update(id, request));
    }

    @Operation(summary = "Referrals, paged (page from 0, size 1 to 100, 25 by default), newest first, by marketer profile and stage, with counts per stage")
    @GetMapping("/referrals")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PageWithCounts<Referral>> referrals(@RequestParam(required = false) String profileId, @RequestParam(required = false) String stage,
                                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(marketers.referrals(profileId, stage, page, size));
    }

    @Operation(summary = "Move a referral to LEAD, CONTACTED, ONBOARDING, LIVE or LOST, with an optional note")
    @PutMapping("/referrals/{id}/stage")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Referral> referralStage(@PathVariable String id, @Valid @RequestBody PlatformRequests.ReferralStageRequest request) {
        return ApiResponse.ok("Updated", marketers.setReferralStage(id, request));
    }

    @Operation(summary = "Commissions, paged (page from 0, size 1 to 100, 25 by default), by marketer profile, status and month, with marketer and "
            + "business names, counts per status and gross, wht, net, due and paid over the filtered range")
    @GetMapping("/commissions")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<PageWithCounts<CommissionRow>> commissions(@RequestParam(required = false) String profileId, @RequestParam(required = false) String status,
                                                                  @RequestParam(required = false) String month,
                                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(marketers.commissions(profileId, status, month, page, size));
    }

    @Operation(summary = "Mark a commission paid, with the transfer reference and date")
    @PostMapping("/commissions/{id}/pay")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<Commission> pay(@PathVariable String id, @RequestBody(required = false) PlatformRequests.PayCommissionRequest request) {
        return ApiResponse.ok("Paid", marketers.pay(id, request));
    }
}
