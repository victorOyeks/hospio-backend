package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.DeliveryService;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.MarketerStatement;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.repository.MarketerStatementRepository;
import com.hospio.platform.settings.FeeSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** The commission rules (docs/plan-marketers.md): 20 % of the platform fee collected, 5 % WHT, 12 months, clawback, minimum payout. */
class CommissionServiceTest {

    private final CommissionRepository commissions = mock(CommissionRepository.class);
    private final MarketerRepository agreements = mock(MarketerRepository.class);
    private final MarketerProfileRepository profiles = mock(MarketerProfileRepository.class);
    private final MarketerStatementRepository statements = mock(MarketerStatementRepository.class);
    private final DailyFeeRepository fees = mock(DailyFeeRepository.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final CommissionService service = new CommissionService(commissions, agreements, profiles, statements, fees, settings,
            mock(DeliveryService.class), mock(AlertService.class), mock(BusinessNames.class), mock(TransactionTemplate.class));
    private final List<Commission> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(commissions.save(any(Commission.class))).thenAnswer(i -> {
            Commission c = i.getArgument(0);
            saved.add(c);
            return c;
        });
        when(statements.save(any(MarketerStatement.class))).thenAnswer(i -> {
            MarketerStatement s = i.getArgument(0);
            s.setId("st-" + s.getMonth());
            return s;
        });
        when(commissions.findBySourceAndSourceRef(anyString(), anyString())).thenReturn(Optional.empty());
        when(settings.fees()).thenReturn(FeeSettings.DEFAULTS);
    }

    static Marketer agreement(String id, LocalDate start, LocalDate end) {
        Marketer m = Marketer.builder().businessId("b1").profileId("p-" + id).userId("u").fullName("Ese " + id).sharePct(new BigDecimal("20"))
                .months(12).whtPct(new BigDecimal("5")).status(end == null ? "ACTIVE" : "ENDED").startedOn(start).endedOn(end).build();
        m.setId(id);
        return m;
    }

    static DailyFee day(LocalDate date, String fee, String carried, String deviceFee, String collected) {
        DailyFee f = DailyFee.builder().businessId("b1").hotelId("h1").feeDate(date).salesTotal(BigDecimal.ZERO).rate(new BigDecimal("1.5"))
                .fee(new BigDecimal(fee)).carriedIn(new BigDecimal(carried)).deviceFee(new BigDecimal(deviceFee))
                .due(new BigDecimal(fee).add(new BigDecimal(carried)).add(new BigDecimal(deviceFee))).collected(new BigDecimal(collected))
                .status(DailyFee.PAID).build();
        f.setId("df-" + date);
        return f;
    }

    @Test
    void twentyPercentOfThePlatformFeeCollectedLessFivePercentWht() {
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(agreement("a1", LocalDate.of(2026, 11, 1), null)));
        when(fees.firstCollectedDate("b1")).thenReturn(LocalDate.of(2026, 11, 15));
        Commission c = service.accrue(day(LocalDate.of(2026, 11, 15), "12000.00", "0", "0", "12000.00"), "HF-1", LocalDate.of(2026, 11, 16));
        assertThat(c.getBaseAmount()).isEqualByComparingTo("12000.00");
        assertThat(c.getGross()).isEqualByComparingTo("2400.00");
        assertThat(c.getWht()).isEqualByComparingTo("120.00");
        assertThat(c.getNet()).isEqualByComparingTo("2280.00");
        assertThat(c.getMonth()).isEqualTo("2026-11");
        assertThat(c.getStatus()).isEqualTo(Commission.ACCRUED);
    }

    @Test
    void theDeviceShareNeverEarns() {
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(agreement("a1", LocalDate.of(2026, 11, 1), null)));
        // fee 1,000 + device 500 due, 1,200 collected: the platform part (paid first) is 1,000
        Commission c = service.accrue(day(LocalDate.of(2026, 11, 15), "1000.00", "0", "500.00", "1200.00"), "HF-1", LocalDate.of(2026, 11, 16));
        assertThat(c.getBaseAmount()).isEqualByComparingTo("1000.00");
        assertThat(c.getGross()).isEqualByComparingTo("200.00");
    }

    @Test
    void aDebitNeverEarnsTwice() {
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(agreement("a1", LocalDate.of(2026, 11, 1), null)));
        DailyFee f = day(LocalDate.of(2026, 11, 15), "1000.00", "0", "0", "1000.00");
        Commission first = service.accrue(f, "HF-1", LocalDate.of(2026, 11, 16));
        when(commissions.findBySourceIdOrderByCreatedAtAsc(f.getId())).thenReturn(List.of(first));
        assertThat(service.accrue(f, "HF-1", LocalDate.of(2026, 11, 16))).isNull();
        assertThat(saved).hasSize(1);
    }

    @Test
    void aPartPaidDayEarnsOnlyWhatWasCollectedAndTheRestLater() {
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(agreement("a1", LocalDate.of(2026, 11, 1), null)));
        DailyFee f = day(LocalDate.of(2026, 11, 15), "1000.00", "0", "0", "400.00");
        Commission first = service.accrue(f, "HF-1", LocalDate.of(2026, 11, 16));
        assertThat(first.getBaseAmount()).isEqualByComparingTo("400.00");
        when(commissions.findBySourceIdOrderByCreatedAtAsc(f.getId())).thenReturn(List.of(first));
        f.setCollected(new BigDecimal("1000.00"));
        Commission second = service.accrue(f, "HF-2", LocalDate.of(2026, 11, 17));
        assertThat(second.getBaseAmount()).isEqualByComparingTo("600.00");
    }

    @Test
    void nothingEarnsAfterTwelveMonthsFromTheFirstCollectedFee() {
        Marketer a = agreement("a1", LocalDate.of(2026, 1, 1), null);
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(a));
        when(fees.firstCollectedDate("b1")).thenReturn(LocalDate.of(2026, 1, 10));
        assertThat(service.accrue(day(LocalDate.of(2027, 1, 9), "1000.00", "0", "0", "1000.00"), "HF-1", LocalDate.of(2027, 1, 10))).isNotNull();
        assertThat(a.getWindowStart()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(a.getWindowEnd()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(service.accrue(day(LocalDate.of(2027, 1, 10), "1000.00", "0", "0", "1000.00"), "HF-2", LocalDate.of(2027, 1, 11))).isNull();
    }

    @Test
    void theAgreementCoveringTheFeeDayEarnsWhenTheMarketerChanged() {
        Marketer old = agreement("old", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18));
        Marketer next = agreement("new", LocalDate.of(2026, 11, 18), null);
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(old, next));
        assertThat(service.agreementFor("b1", LocalDate.of(2026, 11, 17))).isSameAs(old);
        assertThat(service.agreementFor("b1", LocalDate.of(2026, 11, 18))).isSameAs(next);
        assertThat(service.agreementFor("b1", LocalDate.of(2026, 10, 31))).isNull();
    }

    @Test
    void noAgreementNoCommission() {
        when(agreements.findByBusinessId("b1")).thenReturn(List.of());
        assertThat(service.accrue(day(LocalDate.of(2026, 11, 15), "1000.00", "0", "0", "1000.00"), "HF-1", LocalDate.of(2026, 11, 16))).isNull();
        verify(commissions, never()).save(any());
    }

    @Test
    void aRefundClawsBackTheShareOnItAsANegativeLine() {
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(agreement("a1", LocalDate.of(2026, 11, 1), null)));
        DailyFee f = day(LocalDate.of(2026, 11, 15), "1000.00", "0", "0", "1000.00");
        Commission earned = service.accrue(f, "HF-1", LocalDate.of(2026, 11, 16));
        when(commissions.findBySourceIdOrderByCreatedAtAsc(f.getId())).thenReturn(List.of(earned));
        f.setRefunded(new BigDecimal("400.00"));
        Commission back = service.clawback(f, new BigDecimal("400.00"), "Provider reversal", LocalDate.of(2026, 11, 20));
        assertThat(back.getSource()).isEqualTo(Commission.CLAWBACK);
        assertThat(back.getBaseAmount()).isEqualByComparingTo("-400.00");
        assertThat(back.getGross()).isEqualByComparingTo("-80.00");
        assertThat(back.getWht()).isEqualByComparingTo("-4.00");
        assertThat(back.getNet()).isEqualByComparingTo("-76.00");
        // never more than what earned
        when(commissions.findBySourceIdOrderByCreatedAtAsc(f.getId())).thenReturn(List.of(earned, back));
        Commission rest = service.clawback(f, new BigDecimal("900.00"), "Refund", LocalDate.of(2026, 11, 21));
        assertThat(rest.getBaseAmount()).isEqualByComparingTo("-600.00");
    }

    static Commission line(String net, String month) {
        BigDecimal n = new BigDecimal(net);
        BigDecimal gross = n.divide(new BigDecimal("0.95"), 2, java.math.RoundingMode.HALF_UP);
        Commission c = Commission.builder().businessId("b1").marketerId("a1").profileId("p1").month(month).baseAmount(gross.multiply(new BigDecimal("5")))
                .gross(gross).wht(gross.subtract(n)).net(n).status(Commission.ACCRUED).source(Commission.DAILY_FEE).build();
        c.setId("c-" + net);
        return c;
    }

    @Test
    void underTheMinimumTheStatementIsCarriedOverAndPaidWithTheNextOne() {
        MarketerProfile p = MarketerProfile.builder().fullName("Ese").email("ese@hospio-qa.test").build();
        p.setId("p1");
        when(profiles.findById("p1")).thenReturn(Optional.of(p));
        when(statements.findByProfileIdAndMonth(eq("p1"), anyString())).thenReturn(Optional.empty());
        when(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNullAndMonthLessThanEqualOrderByEarnedOnAscCreatedAtAsc("p1", "2026-10"))
                .thenReturn(List.of(line("3000.00", "2026-10")));
        when(statements.findByProfileIdAndStatusAndCarriedToIdIsNullAndMonthLessThan("p1", MarketerStatement.CARRIED_OVER, "2026-10")).thenReturn(List.of());
        MarketerStatement oct = service.makeOne("p1", "2026-10");
        assertThat(oct.getStatus()).isEqualTo(MarketerStatement.CARRIED_OVER);
        assertThat(oct.getPayable()).isEqualByComparingTo("3000.00");
        assertThat(oct.getMinimumPayout()).isEqualByComparingTo("5000.00");

        when(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNullAndMonthLessThanEqualOrderByEarnedOnAscCreatedAtAsc("p1", "2026-11"))
                .thenReturn(List.of(line("2500.00", "2026-11")));
        when(statements.findByProfileIdAndStatusAndCarriedToIdIsNullAndMonthLessThan("p1", MarketerStatement.CARRIED_OVER, "2026-11")).thenReturn(List.of(oct));
        MarketerStatement nov = service.makeOne("p1", "2026-11");
        assertThat(nov.getStatus()).isEqualTo(MarketerStatement.DRAFT);
        assertThat(nov.getNet()).isEqualByComparingTo("2500.00");
        assertThat(nov.getCarriedIn()).isEqualByComparingTo("3000.00");
        assertThat(nov.getPayable()).isEqualByComparingTo("5500.00");
        assertThat(oct.getCarriedToId()).isEqualTo(nov.getId());
        verify(commissions, org.mockito.Mockito.atLeastOnce()).saveAll(anyList());
    }

    @Test
    void aMonthAlreadyMadeIsNotMadeAgain() {
        when(statements.findByProfileIdAndMonth("p1", "2026-11")).thenReturn(Optional.of(MarketerStatement.builder().build()));
        assertThat(service.makeOne("p1", "2026-11")).isNull();
    }

    @Test
    void aFeeDayInsideTheWindowCollectedAfterTheWindowEndedStillEarns() {
        // Agreement ended (marketer changed) and the 12 months ran out before the carried debt for 9 Jan 2027 was collected in March.
        Marketer a = agreement("a1", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 2, 1));
        when(agreements.findByBusinessId("b1")).thenReturn(List.of(a));
        when(fees.firstCollectedDate("b1")).thenReturn(LocalDate.of(2026, 1, 10));
        Commission late = service.accrue(day(LocalDate.of(2027, 1, 9), "1000.00", "0", "0", "1000.00"), "HF-LATE", LocalDate.of(2027, 3, 15));
        assertThat(late).isNotNull();
        assertThat(a.getWindowEnd()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(late.getFeeDate()).isEqualTo(LocalDate.of(2027, 1, 9));
        assertThat(late.getEarnedOn()).isEqualTo(LocalDate.of(2027, 3, 15));
        assertThat(late.getMonth()).isEqualTo("2027-03");
        assertThat(late.getNet()).isEqualByComparingTo("190.00");
    }

    static MarketerProfile ended() {
        MarketerProfile p = MarketerProfile.builder().fullName("Ese").email("ese@hospio-qa.test").status("ENDED").build();
        p.setId("p1");
        return p;
    }

    @Test
    void anEndedMarketersStatementUnderTheMinimumIsAFinalDraftNotCarriedOver() {
        when(profiles.findById("p1")).thenReturn(Optional.of(ended()));
        when(statements.findByProfileIdAndMonth(eq("p1"), anyString())).thenReturn(Optional.empty());
        when(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNullAndMonthLessThanEqualOrderByEarnedOnAscCreatedAtAsc("p1", "2026-12"))
                .thenReturn(List.of(line("997.50", "2026-12")));
        when(statements.findByProfileIdAndStatusAndCarriedToIdIsNullAndMonthLessThan("p1", MarketerStatement.CARRIED_OVER, "2026-12")).thenReturn(List.of());
        MarketerStatement dec = service.makeOne("p1", "2026-12");
        assertThat(dec.getStatus()).isEqualTo(MarketerStatement.DRAFT);
        assertThat(dec.isFinalStatement()).isTrue();
        assertThat(dec.getPayable()).isEqualByComparingTo("997.50");
    }

    @Test
    void endingAMarketerPutsEveryOpenLineAndCarriedBalanceOnAFinalStatement() {
        when(profiles.findById("p1")).thenReturn(Optional.of(ended()));
        MarketerStatement oct = MarketerStatement.builder().profileId("p1").month("2026-10").status(MarketerStatement.CARRIED_OVER)
                .payable(new BigDecimal("3000.00")).build();
        oct.setId("st-2026-10");
        when(statements.findByProfileIdAndStatusAndCarriedToIdIsNull("p1", MarketerStatement.CARRIED_OVER)).thenReturn(List.of(oct));
        Commission dec = line("1000.00", "2026-12");
        when(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNull("p1")).thenReturn(List.of(dec));
        when(statements.findByProfileIdOrderByMonthDesc("p1")).thenReturn(List.of(oct));

        MarketerStatement fin = service.makeFinal("p1", LocalDate.of(2026, 12, 3));

        assertThat(fin.getMonth()).isEqualTo("2026-12");
        assertThat(fin.getStatus()).isEqualTo(MarketerStatement.DRAFT);
        assertThat(fin.isFinalStatement()).isTrue();
        assertThat(fin.getCarriedIn()).isEqualByComparingTo("3000.00");
        assertThat(fin.getPayable()).isEqualByComparingTo("4000.00");
        assertThat(oct.getCarriedToId()).isEqualTo(fin.getId());
        assertThat(dec.getStatementId()).isEqualTo(fin.getId());
        assertThat(dec.getStatus()).isEqualTo(Commission.ON_STATEMENT);
    }

    @Test
    void theFinalStatementTakesTheNextMonthWhenThisMonthsIsAlreadyMade() {
        when(profiles.findById("p1")).thenReturn(Optional.of(ended()));
        MarketerStatement dec = MarketerStatement.builder().profileId("p1").month("2026-12").status(MarketerStatement.PAID).build();
        when(statements.findByProfileIdOrderByMonthDesc("p1")).thenReturn(List.of(dec));
        when(statements.findByProfileIdAndStatusAndCarriedToIdIsNull("p1", MarketerStatement.CARRIED_OVER)).thenReturn(List.of());
        when(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNull("p1")).thenReturn(List.of(line("50.00", "2026-12")));

        assertThat(service.makeFinal("p1", LocalDate.of(2026, 12, 20)).getMonth()).isEqualTo("2027-01");
    }

    @Test
    void nothingOpenNoFinalStatement() {
        when(profiles.findById("p1")).thenReturn(Optional.of(ended()));
        when(statements.findByProfileIdAndStatusAndCarriedToIdIsNull("p1", MarketerStatement.CARRIED_OVER)).thenReturn(List.of());
        when(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNull("p1")).thenReturn(List.of());
        assertThat(service.makeFinal("p1", LocalDate.of(2026, 12, 3))).isNull();
        verify(statements, never()).save(any());
    }
}
