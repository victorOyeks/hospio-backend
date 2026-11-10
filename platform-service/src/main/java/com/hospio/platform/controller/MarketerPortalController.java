package com.hospio.platform.controller;

import com.hospio.common.paging.Paging;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.AdminResponses.CommissionRow;
import com.hospio.platform.dto.AdminResponses.CommissionStatement;
import com.hospio.platform.dto.AdminResponses.MarketerBusiness;
import com.hospio.platform.dto.AdminResponses.MarketerMe;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.Referral;
import com.hospio.platform.service.MarketerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The marketer portal (F): a MARKETER token (scope marketer, no business) sees only its own profile's rows. */
@RestController
@RequestMapping("/marketer")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MARKETER')")
public class MarketerPortalController {

    private final MarketerService marketers;

    @Operation(summary = "My profile with totals: earned, due, paid, businesses, referrals")
    @GetMapping("/me")
    public ApiResponse<MarketerMe> me() {
        return ApiResponse.ok(marketers.me());
    }

    @Operation(summary = "Set my bank details for commission payments")
    @PutMapping("/me/bank")
    public ApiResponse<MarketerProfile> bank(@Valid @RequestBody PlatformRequests.BankRequest request) {
        return ApiResponse.ok("Bank details saved", marketers.updateBank(request));
    }

    @Operation(summary = "My referrals, newest first. With page or size (page from 0, size 1 to 100, 25 by default): one page, by stage, with "
            + "counts per stage; without either, every referral as before")
    @GetMapping("/referrals")
    public ApiResponse<?> referrals(@RequestParam(required = false) String stage, @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        if (Paging.requested(page, size)) {
            return ApiResponse.ok(marketers.myReferrals(stage, page, size));
        }
        return ApiResponse.ok(marketers.myReferrals());
    }

    @Operation(summary = "Refer a hotel: name, owner, phone, email, city, note")
    @PostMapping("/referrals")
    public ApiResponse<Referral> refer(@Valid @RequestBody PlatformRequests.ReferralRequest request) {
        return ApiResponse.ok("Referral sent", marketers.createReferral(request));
    }

    @Operation(summary = "The businesses I referred: name, city, stage, hotels, since, my share and last month's commission")
    @GetMapping("/businesses")
    public ApiResponse<List<MarketerBusiness>> businesses() {
        return ApiResponse.ok(marketers.myBusinesses());
    }

    @Operation(summary = "My commissions, optionally for one month (yyyy-MM). With page or size: one page (latest month first), by status "
            + "(DUE, PAID), with counts and gross, wht, net, due and paid over the filtered range; without either, every line as before")
    @GetMapping("/commissions")
    public ApiResponse<?> commissions(@RequestParam(required = false) String month, @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (Paging.requested(page, size)) {
            return ApiResponse.ok(marketers.myCommissions(month, status, page, size));
        }
        return ApiResponse.ok(marketers.myCommissions(month));
    }

    @Operation(summary = "A plain-text commission statement for a month (defaults to this month)")
    @GetMapping("/commissions/statement")
    public ApiResponse<CommissionStatement> statement(@RequestParam(required = false) String month) {
        return ApiResponse.ok(marketers.myStatement(month));
    }
}
