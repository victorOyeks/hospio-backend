package com.hospio.payment.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.util.Money;
import com.hospio.payment.dto.AdminPaymentDtos.BatchDetail;
import com.hospio.payment.dto.AdminPaymentDtos.CreateBatchRequest;
import com.hospio.payment.dto.AdminPaymentDtos.FailSettlementRequest;
import com.hospio.payment.dto.AdminPaymentDtos.MarkPaidRequest;
import com.hospio.payment.dto.AdminPaymentDtos.RetryResult;
import com.hospio.payment.entity.FailedPayout;
import com.hospio.payment.entity.PayoutBatch;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.repository.FailedPayoutRepository;
import com.hospio.payment.repository.PayoutBatchRepository;
import com.hospio.payment.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Payout batches: bundle pending settlements, mark the batch paid, record a bounced settlement and retry it. */
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final PayoutBatchRepository batches;
    private final SettlementRepository settlements;
    private final FailedPayoutRepository failed;

    @Transactional(readOnly = true)
    public PagedResponse<PayoutBatch> batches(int page, int size) {
        return PagedResponse.of(batches.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), b -> b);
    }

    @Transactional(readOnly = true)
    public BatchDetail batch(String id) {
        PayoutBatch b = find(id);
        return new BatchDetail(b, settlements.findByPayoutBatchIdOrderByBusinessNameAsc(id));
    }

    /** The named settlements (default: every PENDING one) go into a PROCESSING batch and become SETTLED, unpaid until marked. */
    @Transactional
    public BatchDetail create(CreateBatchRequest request) {
        List<Settlement> picked = request == null || request.settlementIds() == null || request.settlementIds().isEmpty()
                ? settlements.findByStatusOrderByPeriodDateAsc(Settlement.Status.PENDING)
                : settlements.findByIdIn(request.settlementIds());
        if (picked.isEmpty()) {
            throw new BadRequestException("There are no pending settlements to pay out");
        }
        for (Settlement s : picked) {
            if (s.getStatus() != Settlement.Status.PENDING) {
                throw new BadRequestException("Settlement " + s.getId() + " is " + s.getStatus().name().toLowerCase() + ", not pending");
            }
        }
        HospioUserDetails user = CurrentUser.require();
        Set<String> businesses = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Settlement s : picked) {
            businesses.add(s.getBusinessId());
            total = total.add(s.getNet());
        }
        PayoutBatch b = batches.save(PayoutBatch.builder().ref(nextRef(LocalDate.now(SettlementService.LAGOS)))
                .createdBy(user.getUserId()).createdByName(user.getFullName()).status(PayoutBatch.Status.PROCESSING)
                .total(Money.of(total)).businessCount(businesses.size()).settlementCount(picked.size()).build());
        for (Settlement s : picked) {
            s.setStatus(Settlement.Status.SETTLED);
            s.setPayoutBatchId(b.getId());
            s.setSettledAt(null);
            settlements.save(s);
        }
        return new BatchDetail(b, picked);
    }

    String nextRef(LocalDate today) {
        String prefix = "PB-" + today.getYear() + "-";
        return prefix + String.format("%03d", batches.countByRefStartingWith(prefix) + 1);
    }

    @Transactional
    public BatchDetail markPaid(String id, MarkPaidRequest request) {
        PayoutBatch b = find(id);
        if (b.getStatus() == PayoutBatch.Status.PAID) {
            throw new BadRequestException("Already marked paid");
        }
        LocalDate paidOn = request == null || request.paidOn() == null ? LocalDate.now(SettlementService.LAGOS) : request.paidOn();
        Instant paidAt = paidOn.atStartOfDay(SettlementService.LAGOS).toInstant();
        b.setStatus(PayoutBatch.Status.PAID);
        b.setPaidAt(paidAt);
        b.setPaidOn(paidOn);
        b.setPaidReference(request == null ? null : request.reference());
        batches.save(b);
        List<Settlement> list = settlements.findByPayoutBatchIdOrderByBusinessNameAsc(id);
        for (Settlement s : list) {
            if (s.getStatus() == Settlement.Status.SETTLED && s.getSettledAt() == null) {
                s.setSettledAt(paidAt);
                settlements.save(s);
            }
        }
        return new BatchDetail(b, list);
    }

    /** The bank bounced one settlement of the batch: it fails, a FailedPayout opens, the batch is PART_FAILED. */
    @Transactional
    public BatchDetail failSettlement(String id, FailSettlementRequest request) {
        PayoutBatch b = find(id);
        if (request == null || request.settlementId() == null) {
            throw new BadRequestException("Say which settlement failed (settlementId)");
        }
        Settlement s = settlements.findById(request.settlementId()).orElseThrow(() -> new ResourceNotFoundException("Settlement not found"));
        if (!id.equals(s.getPayoutBatchId())) {
            throw new BadRequestException("That settlement is not in this batch");
        }
        String reason = request.reason() == null || request.reason().isBlank() ? "Rejected by the bank" : request.reason();
        s.setStatus(Settlement.Status.FAILED);
        s.setSettledAt(null);
        s.setFailureReason(reason);
        settlements.save(s);
        if (failed.findFirstBySettlementIdAndResolvedAtIsNull(s.getId()).isEmpty()) {
            failed.save(FailedPayout.builder().businessId(s.getBusinessId()).hotelId(s.getHotelId()).settlementId(s.getId()).payoutBatchId(b.getId())
                    .businessName(s.getBusinessName()).amount(s.getNet()).bankLabel(request.bankLabel()).reason(reason).attempts(1).lastTriedAt(Instant.now()).build());
        }
        if (b.getStatus() != PayoutBatch.Status.PART_FAILED) {
            b.setStatus(PayoutBatch.Status.PART_FAILED);
            batches.save(b);
        }
        return new BatchDetail(b, settlements.findByPayoutBatchIdOrderByBusinessNameAsc(id));
    }

    @Transactional(readOnly = true)
    public PagedResponse<FailedPayout> failedOpen(int page, int size) {
        return PagedResponse.of(failed.findByResolvedAtIsNullOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), f -> f);
    }

    /** Another attempt: attempts + 1 and the time; with resolve=true the payout is closed and its settlement settled now. */
    @Transactional
    public RetryResult retry(String failedPayoutId, boolean resolve) {
        FailedPayout f = failed.findById(failedPayoutId).orElseThrow(() -> new ResourceNotFoundException("Failed payout not found"));
        if (f.getResolvedAt() != null) {
            throw new BadRequestException("Already resolved");
        }
        f.setAttempts(f.getAttempts() + 1);
        f.setLastTriedAt(Instant.now());
        if (resolve) {
            f.setResolvedAt(Instant.now());
            f.setResolvedBy(CurrentUser.require().getUserId());
            settlements.findById(f.getSettlementId()).ifPresent(s -> {
                s.setStatus(Settlement.Status.SETTLED);
                s.setSettledAt(Instant.now());
                s.setFailureReason(null);
                settlements.save(s);
            });
        }
        failed.save(f);
        return new RetryResult(f.getId(), f.getAttempts(), resolve);
    }

    private PayoutBatch find(String id) {
        return batches.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payout batch not found"));
    }
}
