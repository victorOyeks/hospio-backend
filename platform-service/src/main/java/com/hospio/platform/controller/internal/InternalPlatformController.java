package com.hospio.platform.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.PlatformSettingsCatalogue;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
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

    /** finance's today screen: how many approvals are waiting at a hotel. */
    @GetMapping("/approvals/pending-count")
    public ApiResponse<Long> pendingCount(@org.springframework.web.bind.annotation.RequestParam String hotelId) {
        return ApiResponse.ok(approvals.countByHotelIdAndStatus(hotelId, com.hospio.common.enums.ApprovalStatus.PENDING));
    }

    @GetMapping("/approvals/{id}")
    public ApiResponse<Approval> approval(@PathVariable String id) {
        return ApiResponse.ok(approvals.findById(id).orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Approval not found")));
    }

    /** payment-service settlements: provider fee % and Hospio360 share %, with the rest of the fees group. */
    @GetMapping("/settings/fees")
    public ApiResponse<Map<String, Object>> fees() {
        return ApiResponse.ok(settings.group(PlatformSettingsCatalogue.FEES));
    }
}
