package com.hospio.payment.service;

import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.util.Money;
import com.hospio.payment.dto.AdminPaymentDtos.HotelOverview;
import com.hospio.payment.dto.AdminPaymentDtos.HotelTotal;
import com.hospio.payment.dto.AdminPaymentDtos.PaymentsToday;
import com.hospio.payment.dto.AdminPaymentDtos.ProviderTotal;
import com.hospio.payment.dto.AdminPaymentDtos.Summary;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.repository.ApprovedTotal;
import com.hospio.payment.repository.PaymentRequestRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Cross-business payment reads for the platform: the list, a business's summary, a hotel's figures for today. */
@Service
@RequiredArgsConstructor
public class AdminPaymentQueryService {

    private final PaymentRequestRepository payments;

    @Transactional(readOnly = true)
    public PagedResponse<PaymentResponse> search(String businessId, String hotelId, PaymentStatus status, LocalDate from, LocalDate to, int page, int size) {
        Specification<PaymentRequest> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(businessId)) {
                ps.add(cb.equal(root.get("businessId"), businessId));
            }
            if (StringUtils.hasText(hotelId)) {
                ps.add(cb.equal(root.get("hotelId"), hotelId));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(SettlementService.LAGOS).toInstant()));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(SettlementService.LAGOS).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<PaymentRequest> result = payments.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.of(result, PaymentResponse::of);
    }

    /** Approved payments of a business in [from, to] (defaults: this month), by provider and by hotel. */
    @Transactional(readOnly = true)
    public Summary summary(String businessId, LocalDate from, LocalDate to) {
        YearMonth now = YearMonth.now(SettlementService.LAGOS);
        LocalDate f = from == null ? now.atDay(1) : from;
        LocalDate t = to == null ? now.atEndOfMonth() : to;
        // Summed in the database per hotel, method and provider (a handful of rows), then folded into the two breakdowns.
        List<ApprovedTotal> rows = payments.approvedTotalsForBusiness(PaymentStatus.APPROVED, businessId,
                f.atStartOfDay(SettlementService.LAGOS).toInstant(), t.plusDays(1).atStartOfDay(SettlementService.LAGOS).toInstant());
        Map<String, BigDecimal[]> byProvider = new LinkedHashMap<>();
        Map<String, BigDecimal[]> byHotel = new LinkedHashMap<>();
        BigDecimal gross = BigDecimal.ZERO;
        long count = 0;
        for (ApprovedTotal r : rows) {
            BigDecimal amount = r.amount() == null ? BigDecimal.ZERO : r.amount();
            long n = r.count() == null ? 0 : r.count();
            gross = gross.add(amount);
            count += n;
            add(byProvider, r.method() == com.hospio.common.enums.PaymentMethod.CASH ? "cash" : SettlementService.providerOf(r.provider()), amount, n);
            add(byHotel, r.hotelId(), amount, n);
        }
        List<ProviderTotal> providers = new ArrayList<>();
        byProvider.forEach((k, v) -> providers.add(new ProviderTotal(k, Money.of(v[0]), v[1].longValue())));
        List<HotelTotal> hotels = new ArrayList<>();
        byHotel.forEach((k, v) -> hotels.add(new HotelTotal(k, Money.of(v[0]), v[1].longValue())));
        return new Summary(businessId, f, t, Money.of(gross), count, providers, hotels);
    }

    /** Today's approved payments at a hotel, plus how many are still waiting on the POS. */
    @Transactional(readOnly = true)
    public HotelOverview hotelOverview(String hotelId) {
        LocalDate today = LocalDate.now(SettlementService.LAGOS);
        Instant from = today.atStartOfDay(SettlementService.LAGOS).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(SettlementService.LAGOS).toInstant();
        Object[] row = payments.approvedCountAndSumForHotel(PaymentStatus.APPROVED, hotelId, from, to).stream().findFirst().orElse(new Object[]{0L, null});
        long count = row[0] == null ? 0 : ((Number) row[0]).longValue();
        BigDecimal gross = Money.of(row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
        long pending = payments.countByHotelIdAndStatusIn(hotelId, List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.NO_RESPONSE));
        return new HotelOverview(hotelId, new PaymentsToday(count, gross), pending);
    }

    private static void add(Map<String, BigDecimal[]> m, String key, BigDecimal amount, long count) {
        BigDecimal[] acc = m.computeIfAbsent(key == null ? "unknown" : key.toLowerCase(Locale.ROOT), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        acc[0] = acc[0].add(amount);
        acc[1] = acc[1].add(BigDecimal.valueOf(count));
    }
}
