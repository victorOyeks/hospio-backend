package com.hospio.platform.service;

import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Report;
import com.hospio.platform.repository.ReportRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports (section 7). Fees, marketers, support, health, alerts and changes by Hospio360 each have their own
 * service now ({@link FeeService}, {@link MarketerService}, {@link SupportService}, {@link HealthService},
 * {@link AlertService}, {@link ChangeService}).
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private final ReportRepository reports;

    @Transactional(readOnly = true)
    public List<Report> reports() {
        return reports.findByBusinessIdOrderByCreatedAtDesc(TenantContext.requireBusiness());
    }

    @Transactional
    public Report requestReport(PlatformRequests.ReportRequest request) {
        return reports.save(Report.builder().kind(request.kind().toUpperCase()).periodFrom(request.periodFrom()).periodTo(request.periodTo())
                .schedule(request.schedule()).recipients(request.recipients()).status(request.schedule() == null ? "READY" : "SCHEDULED")
                .requestedBy(CurrentUser.require().getUserId()).build());
    }
}
