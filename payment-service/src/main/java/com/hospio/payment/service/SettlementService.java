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
import com.hospio.payment.repository.ApprovedTotal;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate tx;

    /**
     * The day's approved card and transfer payments are summed in the database per business, hotel, method and provider; business names
     * are read before any transaction is open; each business's settlements are then written in a transaction of their own, so one
     * failure no longer undoes the day. A settlement that is already there (an earlier run, or another instance running now and
     * caught by the unique key) is counted as skipped.
     */
    public RunResult run(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(LAGOS).minusDays(1) : date;
        List<ApprovedTotal> totals = payments.approvedTotalsBetween(PaymentStatus.APPROVED, SETTLING_METHODS,
                day.atStartOfDay(LAGOS).toInstant(), day.plusDays(1).atStartOfDay(LAGOS).toInstant());
        PlatformClient.FeeSettings fees = platform.feeSettings();
        Map<String, List<Settlement>> byBusiness = new LinkedHashMap<>();
        for (Settlement draft : group(totals, day, fees)) {
            byBusiness.computeIfAbsent(draft.getBusinessId(), k -> new ArrayList<>()).add(draft);
        }
        Map<String, String> names = new LinkedHashMap<>();
        byBusiness.keySet().forEach(b -> tenant.businessName(b).ifPresent(n -> names.put(b, n)));
        TransactionTemplate perBusiness = new TransactionTemplate(tx.getTransactionManager());
        perBusiness.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Map.Entry<String, List<Settlement>> e : byBusiness.entrySet()) {
            for (Settlement draft : e.getValue()) {
                draft.setBusinessName(names.get(e.getKey()));
                try {
                    Boolean saved = perBusiness.execute(st -> {
                        if (settlements.existsByBusinessIdAndProviderAndPeriodDate(draft.getBusinessId(), draft.getProvider(), day)) {
                            return false;
                        }
                        settlements.saveAndFlush(draft);
                        return true;
                    });
                    if (Boolean.TRUE.equals(saved)) {
                        created++;
                    } else {
                        skipped++;
                    }
                } catch (DataIntegrityViolationException dup) {
                    skipped++; // another run wrote the same business × provider × day a moment ago
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("Settlement for business {} ({}) on {} failed: {}", draft.getBusinessId(), draft.getProvider(), day, ex.getMessage(), ex);
                }
            }
        }
        log.info("Settlements for {}: {} created, {} already there, {} failed, from {} payment groups", day, created, skipped, failed, totals.size());
        return new RunResult(day, created, skipped);
    }

    /** The nightly job: yesterday's settlements, as the platform. */
    public RunResult runNightly() {
        return TenantContext.runWith(Tenant.system("settlements"), () -> run(LocalDate.now(LAGOS).minusDays(1)));
    }

    /**
     * Pure grouping of the database's sums: one PENDING settlement per business × provider, with the fees computed. Cash is ignored, and
     * so is a transfer without a provider (the desk confirmed it landed in the hotel's bank: nothing to settle).
     */
    public static List<Settlement> group(List<ApprovedTotal> totals, LocalDate day, PlatformClient.FeeSettings fees) {
        Map<String, List<ApprovedTotal>> groups = new LinkedHashMap<>();
        for (ApprovedTotal t : totals) {
            if (!SETTLING_METHODS.contains(t.method()) || t.businessId() == null) {
                continue;
            }
            if (t.method() == PaymentMethod.TRANSFER && !StringUtils.hasText(t.provider())) {
                continue;
            }
            groups.computeIfAbsent(t.businessId() + "|" + providerOf(t.provider()), k -> new ArrayList<>()).add(t);
        }
        List<Settlement> out = new ArrayList<>();
        for (List<ApprovedTotal> ts : groups.values()) {
            ApprovedTotal first = ts.get(0);
            BigDecimal gross = Money.of(ts.stream().map(t -> t.amount() == null ? BigDecimal.ZERO : t.amount()).reduce(BigDecimal.ZERO, BigDecimal::add));
            int count = ts.stream().mapToInt(t -> t.count() == null ? 0 : t.count().intValue()).sum();
            BigDecimal providerFees = Money.percent(gross, fees.providerFeePct());
            BigDecimal hmsShare = Money.percent(gross, fees.hmsSharePct());
            Set<String> hotels = new java.util.HashSet<>();
            ts.forEach(t -> hotels.add(t.hotelId()));
            out.add(Settlement.builder().businessId(first.businessId()).hotelId(hotels.size() == 1 ? first.hotelId() : null)
                    .provider(providerOf(first.provider())).periodDate(day).gross(gross).providerFees(providerFees).hmsShare(hmsShare)
                    .net(gross.subtract(providerFees).subtract(hmsShare)).paymentCount(count).status(Settlement.Status.PENDING).build());
        }
        return out;
    }

    static String providerOf(String provider) {
        return StringUtils.hasText(provider) ? provider.toLowerCase(Locale.ROOT) : "logging";
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
