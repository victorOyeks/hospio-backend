package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.client.HotelSettings;
import com.hospio.finance.client.PeerServices;
import com.hospio.finance.dto.MoneyToday;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.entity.TillEntry;
import com.hospio.finance.repository.BankDepositRepository;
import com.hospio.finance.repository.BudgetRepository;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.RevenueEntryRepository;
import com.hospio.finance.repository.TillEntryRepository;
import com.hospio.finance.repository.TillRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Income by desk: money for a bar or kitchen order is that outlet's income whoever took it (a manager collecting a bar sale is
 * Bar income, front office taking room service is Kitchen income); cash still lands in the payer's own till.
 */
class IncomeDeskTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    private final RevenueEntryRepository revenue = mock(RevenueEntryRepository.class);
    private final TillRepository tills = mock(TillRepository.class);
    private final TillEntryRepository tillEntries = mock(TillEntryRepository.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final PeerServices peers = mock(PeerServices.class);
    private final MoneyService money = new MoneyService(revenue, tills, tillEntries, expenses, mock(BudgetRepository.class),
            mock(BankDepositRepository.class), mock(EventPublisher.class), mock(HotelSettings.class), peers, mock(Notifier.class), mock(ExpenseCategories.class));
    private final ReportingService reporting = new ReportingService(revenue, expenses, mock(BankDepositRepository.class), tillEntries, peers,
            mock(ExpenseCategories.class));

    @BeforeEach
    void signIn() {
        HospioUserDetails user = new HospioUserDetails("u1", "x@x.ng", "Femi", "b1", List.of("h1"), Map.of(Role.FINANCE, AccessLevel.FULL), JwtUtil.SCOPE_HOTEL);
        TenantContext.set(Tenant.fromUser(user, "h1"));
        when(revenue.findByPaymentId(anyString())).thenReturn(Optional.empty());
        when(peers.daySoft(any(), any())).thenReturn(Optional.empty());
        when(peers.outletSales(any(), any())).thenReturn(Optional.empty());
        when(peers.pendingApprovals(any())).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static RevenueEntry entry(String amount, String role, String desk) {
        return RevenueEntry.builder().paymentId(role + amount).businessDate(DAY).amount(new BigDecimal(amount)).method("CASH").role(role).desk(desk).build();
    }

    @Test
    void incomeDeskIsTheOutletForAnOrderElseThePayer() {
        assertThat(RevenueEntry.incomeDesk("BAR", "MANAGER")).isEqualTo("BAR");
        assertThat(RevenueEntry.incomeDesk("KITCHEN", "FRONT_OFFICE")).isEqualTo("KITCHEN");
        assertThat(RevenueEntry.incomeDesk(null, "FRONT_OFFICE")).isEqualTo("FRONT_OFFICE");
        assertThat(RevenueEntry.incomeDesk(" ", "LAUNDRY")).isEqualTo("LAUNDRY");
        assertThat(RevenueEntry.incomeDesk(null, null)).isEqualTo("OTHER");
    }

    @Test
    void aManagerCollectingABarSaleIsBarIncomeInTodaysMoneyAndIncome() {
        when(revenue.findByHotelIdAndBusinessDate("h1", DAY)).thenReturn(List.of(entry("5000", "BAR", "BAR"), entry("2400", "MANAGER", "BAR"),
                entry("9000", "FRONT_OFFICE", "KITCHEN"), entry("40000", "FRONT_OFFICE", null)));
        // income() reads [desk, role, sum] rows summed in the database
        when(revenue.sumByDeskAndRole("h1", DAY, DAY)).thenReturn(List.of(new Object[]{"BAR", "BAR", new BigDecimal("5000")},
                new Object[]{"BAR", "MANAGER", new BigDecimal("2400")}, new Object[]{"KITCHEN", "FRONT_OFFICE", new BigDecimal("9000")},
                new Object[]{null, "FRONT_OFFICE", new BigDecimal("40000")}));

        MoneyToday today = money.today(DAY);

        assertThat(today.byRole()).containsOnlyKeys("BAR", "KITCHEN", "FRONT_OFFICE");
        assertThat(today.byRole().get("BAR")).isEqualByComparingTo("7400");
        assertThat(today.byRole().get("KITCHEN")).isEqualByComparingTo("9000");
        assertThat(today.byRole().get("FRONT_OFFICE")).isEqualByComparingTo("40000");
        assertThat(today.revenue()).isEqualByComparingTo("56400");

        Map<String, BigDecimal> income = new java.util.HashMap<>();
        reporting.income(DAY, DAY).forEach(s -> income.put(s.source(), s.amount()));
        assertThat(income.get("Bar")).isEqualByComparingTo("7400");
        assertThat(income.get("Kitchen & restaurant")).isEqualByComparingTo("9000");
        assertThat(income.get("Rooms & front desk")).isEqualByComparingTo("40000");
    }

    @Test
    void cashForAnOrderLandsInThePayersTillAndKeepsTheOutletAsIncomeDesk() {
        Till frontDesk = Till.builder().role("FRONT_OFFICE").status(Till.Status.OPEN).openingFloat(BigDecimal.ZERO).expectedCash(BigDecimal.ZERO).build();
        frontDesk.setId("t-fo");
        when(tills.lockOpen("h1", "FRONT_OFFICE")).thenReturn(List.of(frontDesk));
        when(tills.lockStatus("t-fo")).thenReturn(Optional.of("OPEN"));
        when(tillEntries.findByPaymentId("p1")).thenReturn(Optional.empty());
        when(tillEntries.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tillEntries.findByTillIdOrderByCreatedAt(any())).thenReturn(List.of());

        money.recordPayment("p1", new BigDecimal("9000"), "CASH", "FRONT_OFFICE", null, Instant.parse("2026-11-10T12:00:00Z"), "Guest", "o1", "KITCHEN");

        ArgumentCaptor<RevenueEntry> saved = ArgumentCaptor.forClass(RevenueEntry.class);
        verify(revenue).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo("FRONT_OFFICE");
        assertThat(saved.getValue().getDesk()).isEqualTo("KITCHEN");
        assertThat(saved.getValue().getOrderId()).isEqualTo("o1");
        assertThat(saved.getValue().incomeDesk()).isEqualTo("KITCHEN");
        assertThat(saved.getValue().isUnattachedCash()).isFalse();
        ArgumentCaptor<TillEntry> te = ArgumentCaptor.forClass(TillEntry.class);
        verify(tillEntries).save(te.capture());
        assertThat(te.getValue().getTillId()).isEqualTo("t-fo");
    }

    @Test
    void aManagerHasNoTillSoTheirCashIsUnattachedButStillBarIncome() {
        money.recordPayment("p2", new BigDecimal("2400"), "CASH", "MANAGER", null, Instant.parse("2026-11-10T12:00:00Z"), "Guest", "o2", "BAR");

        ArgumentCaptor<RevenueEntry> saved = ArgumentCaptor.forClass(RevenueEntry.class);
        verify(revenue).save(saved.capture());
        assertThat(saved.getValue().incomeDesk()).isEqualTo("BAR");
        verify(tills, never()).lockOpen("h1", "BAR");
    }
}
