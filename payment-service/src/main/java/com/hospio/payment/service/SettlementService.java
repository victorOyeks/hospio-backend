package com.hospio.payment.service;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.payment.client.PlatformClient;
import com.hospio.payment.client.TenantClient;
import com.hospio.payment.dto.AdminPaymentDtos.RunResult;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.SettlementRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Settlements: a day's approved card and transfer payments, one row per business and provider, net of the
 * provider's fee and Hospio360's share (both percentages from platform settings). Run nightly for yesterday and on
 * demand for any date; a business × provider × day that already has a settlement is left alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final Set<PaymentMethod> SETTLING_METHODS = Set.of(PaymentMethod.CARD, PaymentMethod.TRANSFER);

    private final PaymentRequestRepository payments;
    private final SettlementRepository settlements;
    private final PlatformClient platform;
    private final TenantClient tenant;

    @Transactional
    public RunResult run(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(LAGOS).minusDays(1) : date;
        List<PaymentRequest> approved = payments.approvedBetween(PaymentStatus.APPROVED, SETTLING_METHODS,
                day.atStartOfDay(LAGOS).toInstant(), day.plusDays(1).atStartOfDay(LAGOS).toInstant());
        PlatformClient.FeeSettings fees = platform.feeSettings();
        int created = 0;
        int skipped = 0;
        for (Settlement draft : group(approved, day, fees)) {
            if (settlements.existsByBusinessIdAndProviderAndPeriodDate(draft.getBusinessId(), draft.getProvider(), day)) {
                skipped++;
                continue;
            }
            draft.setBusinessName(tenant.businessName(draft.getBusinessId()).orElse(null));
            settlements.save(draft);
            created++;
        }
        log.info("Settlements for {}: {} created, {} already there, from {} approved card/transfer payments", day, created, skipped, approved.size());
        return new RunResult(day, created, skipped);
    }

    /** The nightly job: yesterday's settlements, as the platform. */
    @Transactional
    public RunResult runNightly() {
        return TenantContext.runWith(Tenant.system("settlements"), () -> run(LocalDate.now(LAGOS).minusDays(1)));
    }

    /** Pure grouping: one PENDING settlement per business × provider, with the fees computed. Cash is ignored. */
    public static List<Settlement> group(List<PaymentRequest> approved, LocalDate day, PlatformClient.FeeSettings fees) {
        Map<String, List<PaymentRequest>> groups = new LinkedHashMap<>();
        for (PaymentRequest p : approved) {
            if (p.getStatus() != PaymentStatus.APPROVED || !SETTLING_METHODS.contains(p.getMethod()) || p.getBusinessId() == null) {
                continue;
            }
            // A transfer the desk confirmed went straight to the hotel's bank: no provider, nothing to settle.
            if (p.getMethod() == PaymentMethod.TRANSFER && !StringUtils.hasText(p.getProvider())) {
                continue;
            }
            groups.computeIfAbsent(p.getBusinessId() + "|" + providerOf(p), k -> new ArrayList<>()).add(p);
        }
        List<Settlement> out = new ArrayList<>();
        for (List<PaymentRequest> ps : groups.values()) {
            PaymentRequest first = ps.get(0);
            BigDecimal gross = Money.of(ps.stream().map(PaymentRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal providerFees = Money.percent(gross, fees.providerFeePct());
            BigDecimal hmsShare = Money.percent(gross, fees.hmsSharePct());
            Set<String> hotels = new java.util.HashSet<>();
            ps.forEach(p -> hotels.add(p.getHotelId()));
            out.add(Settlement.builder().businessId(first.getBusinessId()).hotelId(hotels.size() == 1 ? first.getHotelId() : null)
                    .provider(providerOf(first)).periodDate(day).gross(gross).providerFees(providerFees).hmsShare(hmsShare)
                    .net(gross.subtract(providerFees).subtract(hmsShare)).paymentCount(ps.size()).status(Settlement.Status.PENDING).build());
        }
        return out;
    }

    static String providerOf(PaymentRequest p) {
        return StringUtils.hasText(p.getProvider()) ? p.getProvider().toLowerCase(Locale.ROOT) : "logging";
    }

    @Transactional(readOnly = true)
    public PagedResponse<Settlement> search(String businessId, Settlement.Status status, LocalDate from, LocalDate to, int page, int size) {
        Specification<Settlement> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(businessId)) {
                ps.add(cb.equal(root.get("businessId"), businessId));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("periodDate"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("periodDate"), to));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Settlement> result = settlements.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "periodDate").and(Sort.by("businessName"))));
        return PagedResponse.of(result, s -> s);
    }
}
