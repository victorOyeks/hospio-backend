package com.hospio.payment.dto;

import com.hospio.payment.entity.PayoutBatch;
import com.hospio.payment.entity.Settlement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes of /admin/** in payment-service. */
public final class AdminPaymentDtos {

    private AdminPaymentDtos() {
    }

    public record RunSettlementsRequest(LocalDate date) {
    }

    public record RunResult(LocalDate date, int created, int skipped) {
    }

    public record CreateBatchRequest(List<String> settlementIds) {
    }

    public record MarkPaidRequest(String reference, LocalDate paidOn) {
    }

    public record FailSettlementRequest(String settlementId, String reason, String bankLabel) {
    }

    public record RetryResult(String failedPayoutId, int attempts, boolean resolved) {
    }

    public record BatchDetail(PayoutBatch batch, List<Settlement> settlements) {
    }

    public record ProviderTotal(String provider, BigDecimal gross, long count) {
    }

    public record HotelTotal(String hotelId, BigDecimal gross, long count) {
    }

    public record Summary(String businessId, LocalDate from, LocalDate to, BigDecimal gross, long count, List<ProviderTotal> byProvider, List<HotelTotal> byHotel) {
    }

    public record PaymentsToday(long count, BigDecimal gross) {
    }

    public record HotelOverview(String hotelId, PaymentsToday paymentsToday, long pendingPayments) {
    }
}
