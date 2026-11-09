package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.payment.client.PlatformClient;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.repository.FailedPayoutRepository;
import com.hospio.payment.repository.PayoutBatchRepository;
import com.hospio.payment.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One settlement per business × provider; cash never settles; fees come off the gross; batch refs count per year. */
class SettlementServiceTest {

    private static PaymentRequest payment(String business, String hotel, PaymentMethod method, String provider, String amount, PaymentStatus status) {
        return PaymentRequest.builder().businessId(business).hotelId(hotel).method(method).provider(provider).amount(new BigDecimal(amount))
                .status(status).requestedBy("u1").role(Role.FRONT_OFFICE).build();
    }

    @Test
    void groupsApprovedCardAndTransferPaymentsPerBusinessAndProvider() {
        List<PaymentRequest> approved = List.of(
                payment("b1", "h1", PaymentMethod.CARD, "logging", "100000", PaymentStatus.APPROVED),
                payment("b1", "h2", PaymentMethod.TRANSFER, "logging", "50000", PaymentStatus.APPROVED),
                payment("b1", "h1", PaymentMethod.CARD, "moniepoint", "20000", PaymentStatus.APPROVED),
                payment("b1", "h1", PaymentMethod.CASH, null, "999999", PaymentStatus.APPROVED),
                payment("b2", "h3", PaymentMethod.CARD, null, "80000", PaymentStatus.APPROVED),
                payment("b2", "h3", PaymentMethod.CARD, "logging", "1", PaymentStatus.DECLINED));

        List<Settlement> out = SettlementService.group(approved, LocalDate.of(2026, 11, 7), PlatformClient.FeeSettings.defaults());

        assertThat(out).hasSize(3);
        Settlement b1Logging = out.stream().filter(s -> s.getBusinessId().equals("b1") && s.getProvider().equals("logging")).findFirst().orElseThrow();
        assertThat(b1Logging.getGross()).isEqualByComparingTo("150000.00");
        assertThat(b1Logging.getProviderFees()).isEqualByComparingTo("750.00");
        assertThat(b1Logging.getHmsShare()).isEqualByComparingTo("2250.00");
        assertThat(b1Logging.getNet()).isEqualByComparingTo("147000.00");
        assertThat(b1Logging.getPaymentCount()).isEqualTo(2);
        assertThat(b1Logging.getHotelId()).isNull();
        assertThat(b1Logging.getStatus()).isEqualTo(Settlement.Status.PENDING);
        assertThat(b1Logging.getPeriodDate()).isEqualTo(LocalDate.of(2026, 11, 7));

        Settlement b2 = out.stream().filter(s -> s.getBusinessId().equals("b2")).findFirst().orElseThrow();
        assertThat(b2.getProvider()).isEqualTo("logging");
        assertThat(b2.getHotelId()).isEqualTo("h3");
        assertThat(b2.getGross()).isEqualByComparingTo("80000.00");
    }

    @Test
    void feePercentagesComeFromTheSettings() {
        List<Settlement> out = SettlementService.group(List.of(payment("b1", "h1", PaymentMethod.CARD, "logging", "10000", PaymentStatus.APPROVED)),
                LocalDate.of(2026, 11, 7), new PlatformClient.FeeSettings(new BigDecimal("1"), new BigDecimal("3")));
        assertThat(out.get(0).getProviderFees()).isEqualByComparingTo("100.00");
        assertThat(out.get(0).getHmsShare()).isEqualByComparingTo("300.00");
        assertThat(out.get(0).getNet()).isEqualByComparingTo("9600.00");
    }

    @Test
    void batchRefsAreNumberedPerYear() {
        PayoutBatchRepository batches = mock(PayoutBatchRepository.class);
        when(batches.countByRefStartingWith("PB-2026-")).thenReturn(41L);
        PayoutService payouts = new PayoutService(batches, mock(SettlementRepository.class), mock(FailedPayoutRepository.class));
        assertThat(payouts.nextRef(LocalDate.of(2026, 11, 8))).isEqualTo("PB-2026-042");
    }
}
