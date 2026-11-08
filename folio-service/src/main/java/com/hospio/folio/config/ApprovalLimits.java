package com.hospio.folio.config;

import java.math.BigDecimal;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Defaults from config; a hotel's own limits in Hotel setup (APPROVAL_LIMITS) override them when set. */
@Getter
@Component
public class ApprovalLimits {

    @Value("${hospio.approvals.manager-discount-limit:50000}")
    private BigDecimal managerDiscountLimit;

    @Value("${hospio.approvals.org-credit-owner-limit:1000000}")
    private BigDecimal orgCreditOwnerLimit;
}
