package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.payment.client.PlatformClient;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.client.TenantClient;
import com.hospio.payment.repository.ApprovedTotal;
import com.hospio.payment.repository.FailedPayoutRepository;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.PayoutBatchRepository;
import com.hospio.payment.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

/** One settlement per business × provider; cash never settles; fees come off the gross; batch refs count per year. */
class SettlementServiceTest {

    private static ApprovedTotal row(String business, String hotel, PaymentMethod method, String provider, String amount, long count) {
        return new ApprovedTotal(business, hotel, method, provider, new BigDecimal(amount), count);
    }

    @Test
    void groupsApprovedCardAndTransferSumsPerBusinessAndProvider() {
        List<ApprovedTotal> totals = List.of(
                row("b1", "h1", PaymentMethod.CARD, "logging", "100000", 1),
                row("b1", "h2", PaymentMethod.TRANSFER, "Logging", "50000", 1),
                row("b1", "h1", PaymentMethod.CARD, "moniepoint", "20000", 1),
                row("b1", "h1", PaymentMethod.CASH, null, "999999", 4),
                row("b1", "h1", PaymentMethod.TRANSFER, null, "70000", 2),
                row("b2", "h3", PaymentMethod.CARD, null, "80000", 3),
                row(null, "h9", PaymentMethod.CARD, "logging", "5", 1));

        List<Settlement> out = SettlementService.group(totals, LocalDate.of(2026, 11, 7), PlatformClient.FeeSettings.defaults());

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
        assertThat(b2.getPaymentCount()).isEqualTo(3);
    }

    @Test
    void feePercentagesComeFromTheSettings() {
        List<Settlement> out = SettlementService.group(List.of(row("b1", "h1", PaymentMethod.CARD, "logging", "10000", 1)),
                LocalDate.of(2026, 11, 7), new PlatformClient.FeeSettings(new BigDecimal("1"), new BigDecimal("3")));
        assertThat(out.get(0).getProviderFees()).isEqualByComparingTo("100.00");
        assertThat(out.get(0).getHmsShare()).isEqualByComparingTo("300.00");
        assertThat(out.get(0).getNet()).isEqualByComparingTo("9600.00");
    }

    /** Names are read before any transaction; one already there or taken by a parallel run is skipped; one failure doesn't stop the rest. */
    @Test
    void runWritesEachSettlementOnItsOwnAndSkipsDuplicates() {
        PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
        SettlementRepository settlements = mock(SettlementRepository.class);
        PlatformClient platform = mock(PlatformClient.class);
        TenantClient tenant = mock(TenantClient.class);
        LocalDate day = LocalDate.of(2026, 11, 7);
        when(payments.approvedTotalsBetween(any(), any(), any(), any())).thenReturn(List.of(
                row("b1", "h1", PaymentMethod.CARD, "logging", "1000", 1),
                row("b2", "h2", PaymentMethod.CARD, "logging", "2000", 1),
                row("b3", "h3", PaymentMethod.CARD, "logging", "3000", 1),
                row("b4", "h4", PaymentMethod.CARD, "logging", "4000", 1)));
        when(platform.feeSettings()).thenReturn(PlatformClient.FeeSettings.defaults());
        when(tenant.businessName(anyString())).thenAnswer(i -> Optional.of("Name of " + i.getArgument(0)));
        when(settlements.existsByBusinessIdAndProviderAndPeriodDate("b1", "logging", day)).thenReturn(true);
        when(settlements.saveAndFlush(any())).thenAnswer(i -> {
            Settlement s = i.getArgument(0);
            if (s.getBusinessId().equals("b2")) {
                throw new DataIntegrityViolationException("duplicate key");
            }
            if (s.getBusinessId().equals("b3")) {
                throw new IllegalStateException("database went away");
            }
            return s;
        });
        SettlementService service = new SettlementService(payments, settlements, platform, tenant,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));

        var result = service.run(day);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlements, org.mockito.Mockito.times(3)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues().get(2).getBusinessName()).isEqualTo("Name of b4");
    }

    @Test
    void batchRefsAreNumberedPerYear() {
        PayoutBatchRepository batches = mock(PayoutBatchRepository.class);
        when(batches.countByRefStartingWith("PB-2026-")).thenReturn(41L);
        PayoutService payouts = new PayoutService(batches, mock(SettlementRepository.class), mock(FailedPayoutRepository.class));
        assertThat(payouts.nextRef(LocalDate.of(2026, 11, 8))).isEqualTo("PB-2026-042");
    }

    /** A settlement another batch took a moment ago (the guarded update moves fewer rows than were read) stops the batch. */
    @Test
    void aSettlementAlreadyInAnotherBatchStopsTheBatch() {
        PayoutBatchRepository batches = mock(PayoutBatchRepository.class);
        SettlementRepository settlements = mock(SettlementRepository.class);
        Settlement a = Settlement.builder().businessId("b1").provider("logging").net(new BigDecimal("100")).status(Settlement.Status.PENDING).build();
        a.setId("s1");
        Settlement b = Settlement.builder().businessId("b2").provider("logging").net(new BigDecimal("200")).status(Settlement.Status.PENDING).build();
        b.setId("s2");
        when(settlements.findByStatusOrderByPeriodDateAsc(Settlement.Status.PENDING)).thenReturn(List.of(a, b));
        when(batches.save(any())).thenAnswer(i -> {
            com.hospio.payment.entity.PayoutBatch pb = i.getArgument(0);
            pb.setId("pb1");
            return pb;
        });
        when(settlements.claimForBatch(any(), eq("pb1"), any())).thenReturn(1);
        com.hospio.common.security.HospioUserDetails admin = new com.hospio.common.security.HospioUserDetails("a1", "a@x.ng", "Admin", null, List.of(),
                java.util.Map.of(), com.hospio.common.security.JwtUtil.SCOPE_HOTEL);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        try {
            PayoutService payouts = new PayoutService(batches, settlements, mock(FailedPayoutRepository.class));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> payouts.create(null))
                    .isInstanceOf(com.hospio.common.exception.ConflictException.class).hasMessageContaining("another payout batch");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
