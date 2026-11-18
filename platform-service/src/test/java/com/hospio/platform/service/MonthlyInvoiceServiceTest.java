package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.billing.BillingHold;
import com.hospio.common.billing.MessageBillingDtos;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.client.MessageBillingClient;
import com.hospio.platform.client.PlatformPaystackClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.DeviceInvoice;
import com.hospio.platform.entity.MonthlyInvoice;
import com.hospio.platform.entity.MonthlyInvoiceEvent;
import com.hospio.platform.entity.MonthlyInvoiceLine;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import com.hospio.platform.repository.MonthlyInvoiceEventRepository;
import com.hospio.platform.repository.MonthlyInvoiceLineRepository;
import com.hospio.platform.repository.MonthlyInvoicePaymentRepository;
import com.hospio.platform.repository.MonthlyInvoiceRepository;
import com.hospio.platform.settings.FeeSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** docs/plan-monthly-invoices.md: the timeline, what an invoice bills, the minimum, the staging guard. */
class MonthlyInvoiceServiceTest {

    static final LocalDate ISSUED = LocalDate.of(2026, 12, 1);

    MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    MonthlyInvoiceLineRepository lines = mock(MonthlyInvoiceLineRepository.class);
    MonthlyInvoiceEventRepository events = mock(MonthlyInvoiceEventRepository.class);
    DailyFeeRepository fees = mock(DailyFeeRepository.class);
    DeviceInvoiceRepository deviceItems = mock(DeviceInvoiceRepository.class);
    MessageBillingClient messages = mock(MessageBillingClient.class);
    TenantClient tenant = mock(TenantClient.class);
    PlatformSettingsService settings = mock(PlatformSettingsService.class);
    DeliveryService deliveries = mock(DeliveryService.class);
    List<MonthlyInvoiceLine> savedLines = new ArrayList<>();
    MonthlyInvoiceService service;

    @SuppressWarnings("unchecked")
    MonthlyInvoiceService service(boolean qaOnly) {
        return new MonthlyInvoiceService(invoices, lines, mock(MonthlyInvoicePaymentRepository.class), events, fees, deviceItems,
                mock(DeviceInvoiceService.class), mock(DevicePackageService.class), mock(FeeCollectionRepository.class), mock(FeeDebitClient.class), messages,
                tenant, settings, mock(PlatformPaystackClient.class), deliveries, mock(ObjectProvider.class), mock(CommissionService.class),
                mock(BillingHold.class), new TransactionTemplate(mock(PlatformTransactionManager.class)), "https://web-staging.hospio360.com", qaOnly, "");
    }

    @BeforeEach
    void setUp() {
        service = service(false);
        when(settings.fees()).thenReturn(FeeSettings.DEFAULTS);
        when(invoices.save(any())).thenAnswer(a -> {
            MonthlyInvoice i = a.getArgument(0);
            if (i.getId() == null) {
                i.setId("inv1");
            }
            return i;
        });
        when(lines.save(any())).thenAnswer(a -> {
            savedLines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(invoices.findFirstByBusinessIdAndMonthAndStatusNot(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(invoices.findByBusinessIdAndStatusAndCarriedIntoIdIsNull(anyString(), anyString())).thenReturn(List.of());
        when(deviceItems.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndDueOnLessThanEqualOrderByDueOnAsc(anyString(), any(), any())).thenReturn(List.of());
        when(messages.toBill(anyString(), anyString())).thenReturn(List.of());
        when(tenant.business(anyString())).thenReturn(Optional.empty());
    }

    static MonthlyInvoice invoice(LocalDate issued) {
        return MonthlyInvoice.builder().businessId("b1").businessName("QA Lagos").number("HIN-202611-0001").month("2026-11").issuedOn(issued)
                .dueOn(issued.plusDays(MonthlyInvoiceService.DUE_DAYS)).graceUntil(issued.plusDays(MonthlyInvoiceService.DUE_DAYS + MonthlyInvoiceService.GRACE_DAYS))
                .total(new BigDecimal("54320.00")).status(MonthlyInvoice.DUE).build();
    }

    static DailyFee day(String hotel, String name, int d, String sales, String fee) {
        DailyFee f = DailyFee.builder().businessId("b1").hotelId(hotel).hotelName(name).feeDate(LocalDate.of(2026, 11, d)).salesTotal(new BigDecimal(sales))
                .rate(new BigDecimal("1.5")).fee(new BigDecimal(fee)).due(new BigDecimal(fee)).status(DailyFee.ACCRUED).build();
        f.setId("df-" + hotel + "-" + d);
        return f;
    }

    static TenantClient.Business business(String name, String email) {
        return new TenantClient.Business("b1", name, "Lagos", "LIVE", null, null, "Ada", email, null, "PERCENT_OF_SALES", new BigDecimal("1.5"), null, null,
                null, null, 2, List.of(new TenantClient.Hotel("h1", "Ikeja", "Lagos", 10, "ACTIVE", null), new TenantClient.Hotel("h2", "Lekki", "Lagos", 8, "ACTIVE", null)));
    }

    // ---- the timeline ---------------------------------------------------------------------------------

    @Test
    void issuedOnTheFirstDueOnTheEighthGraceToTheEleventhPauseFromTheTwelfth() {
        MonthlyInvoice i = invoice(ISSUED);
        assertThat(i.getDueOn()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(i.lastDay()).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(MonthlyInvoiceService.restrictFrom(i)).isEqualTo(java.time.Instant.parse("2026-12-12T05:00:00Z")); // 06:00 Lagos

        List<String> notices = new ArrayList<>();
        for (int d = 1; d <= 12; d++) {
            notices.add(d + ":" + MonthlyInvoiceService.noticeFor(i, LocalDate.of(2026, 12, d)));
        }
        assertThat(notices).containsExactly("1:null", "2:null", "3:null", "4:null", "5:null", "6:REMINDER", "7:REMINDER", "8:DUE_TODAY", "9:OVERDUE",
                "10:OVERDUE", "11:FINAL_WARNING", "12:null");
        i.setRestrictedAt(java.time.Instant.now());
        assertThat(MonthlyInvoiceService.noticeFor(i, LocalDate.of(2026, 12, 12))).isEqualTo(MonthlyInvoiceEvent.RESTRICTED);
    }

    @Test
    void anExtensionMovesTheFinalWarningAndThePause() {
        MonthlyInvoice i = invoice(ISSUED);
        i.setExtendedUntil(LocalDate.of(2026, 12, 15));
        assertThat(MonthlyInvoiceService.noticeFor(i, LocalDate.of(2026, 12, 11))).isEqualTo(MonthlyInvoiceEvent.OVERDUE);
        assertThat(MonthlyInvoiceService.noticeFor(i, LocalDate.of(2026, 12, 15))).isEqualTo(MonthlyInvoiceEvent.FINAL_WARNING);
        assertThat(MonthlyInvoiceService.restrictFrom(i)).isEqualTo(java.time.Instant.parse("2026-12-16T05:00:00Z"));
    }

    @Test
    void theBannerFollowsTheSameDays() {
        MonthlyInvoice i = invoice(ISSUED);
        assertThat(MonthlyInvoiceService.statusState(i, LocalDate.of(2026, 12, 2))).isEqualTo("DUE");
        assertThat(MonthlyInvoiceService.statusState(i, LocalDate.of(2026, 12, 6))).isEqualTo("DUE_SOON");
        assertThat(MonthlyInvoiceService.statusState(i, LocalDate.of(2026, 12, 8))).isEqualTo("DUE_TODAY");
        assertThat(MonthlyInvoiceService.statusState(i, LocalDate.of(2026, 12, 9))).isEqualTo("OVERDUE");
        assertThat(MonthlyInvoiceService.statusState(i, LocalDate.of(2026, 12, 11))).isEqualTo("FINAL_WARNING");
        i.setRestrictedAt(java.time.Instant.now());
        assertThat(MonthlyInvoiceService.statusState(i, LocalDate.of(2026, 12, 12))).isEqualTo("RESTRICTED");
        i.setRestrictionLiftedAt(java.time.Instant.now());
        assertThat(i.restricted()).isFalse();
    }

    // ---- what an invoice bills ---------------------------------------------------------------------------

    @Test
    void oneInvoiceWithPlatformFeePerLocationGuestMessagesAndDevices() {
        DailyFee a1 = day("h1", "Ikeja", 1, "1000000", "15000");
        DailyFee a2 = day("h1", "Ikeja", 2, "600000", "9000");
        DailyFee b1 = day("h2", "Lekki", 1, "200000", "3000");
        when(fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(eq("b1"), any(), eq(LocalDate.of(2026, 11, 30))))
                .thenReturn(List.of(a1, a2, b1));
        when(messages.toBill("b1", "2026-11")).thenReturn(List.of(new MessageBillingDtos.ToBill("m1", "MSG-202611-0004", "2026-11", "h1", "Ikeja", 12, 3, 0,
                new BigDecimal("90.00"))));
        DeviceInvoice item = DeviceInvoice.builder().businessId("b1").hotelId("h2").hotelName("Lekki").packageId("pk1").number("DEV-202611-0002")
                .kind(DeviceInvoice.INSTALMENT).sequence(2).ofCount(3).amount(new BigDecimal("50000")).dueOn(LocalDate.of(2026, 11, 20)).status(DeviceInvoice.DUE).build();
        item.setId("dv1");
        when(deviceItems.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndDueOnLessThanEqualOrderByDueOnAsc(eq("b1"), any(), any())).thenReturn(List.of(item));

        MonthlyInvoice inv = service.issue(business("QA Lagos", "ada@hospio-qa.test"), YearMonth.of(2026, 11), ISSUED, null);

        assertThat(inv.getNumber()).isEqualTo("HIN-202611-0001");
        assertThat(inv.getStatus()).isEqualTo(MonthlyInvoice.DUE);
        assertThat(inv.getPlatformTotal()).isEqualByComparingTo("27000");
        assertThat(inv.getMessagesTotal()).isEqualByComparingTo("90");
        assertThat(inv.getDevicesTotal()).isEqualByComparingTo("50000");
        assertThat(inv.getVatAmount()).as("no VAT unless the setting says so").isZero();
        assertThat(inv.getTotal()).isEqualByComparingTo("77090");
        assertThat(inv.getDueOn()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(inv.getGraceUntil()).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(savedLines).extracting(MonthlyInvoiceLine::getSection, MonthlyInvoiceLine::getLabel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PLATFORM", "Platform fee · Ikeja"), org.assertj.core.groups.Tuple.tuple("PLATFORM", "Platform fee · Lekki"),
                        org.assertj.core.groups.Tuple.tuple("MESSAGES", "Guest messages · Ikeja · November 2026"),
                        org.assertj.core.groups.Tuple.tuple("DEVICES", "Devices instalment 2 of 3 · Lekki"));
        assertThat(savedLines.get(0).getBaseAmount()).isEqualByComparingTo("1600000");
        assertThat(savedLines.get(0).getRate()).isEqualByComparingTo("1.5");
        assertThat(a1.getMonthlyInvoiceId()).isEqualTo("inv1");
        assertThat(a1.getStatus()).isEqualTo(DailyFee.INVOICED);
        assertThat(item.getMonthlyInvoiceNumber()).isEqualTo("HIN-202611-0001");
        verify(messages).link(new MessageBillingDtos.Link("inv1", "HIN-202611-0001", List.of("m1")));
    }

    @Test
    void underOneThousandNairaIsCarriedForwardAndTheNextInvoiceTakesIt() {
        when(fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(eq("b1"), any(), any()))
                .thenReturn(List.of(day("h1", "Ikeja", 3, "42000", "630")));
        MonthlyInvoice nov = service.issue(business("QA Lagos", "ada@hospio-qa.test"), YearMonth.of(2026, 11), ISSUED, null);
        assertThat(nov.getStatus()).isEqualTo(MonthlyInvoice.CARRIED_FORWARD);
        assertThat(nov.getTotal()).isEqualByComparingTo("630");

        savedLines.clear();
        nov.setId("nov");
        when(invoices.findByBusinessIdAndStatusAndCarriedIntoIdIsNull("b1", MonthlyInvoice.CARRIED_FORWARD)).thenReturn(List.of(nov));
        when(fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(eq("b1"), any(), any()))
                .thenReturn(List.of(day("h1", "Ikeja", 4, "100000", "1500")));
        org.mockito.Mockito.doAnswer(a -> {
            MonthlyInvoice i = a.getArgument(0);
            if (i.getId() == null) {
                i.setId("dec");
            }
            return i;
        }).when(invoices).save(any());
        MonthlyInvoice dec = service.issue(business("QA Lagos", "ada@hospio-qa.test"), YearMonth.of(2026, 12), LocalDate.of(2027, 1, 1), null);
        assertThat(dec.getStatus()).isEqualTo(MonthlyInvoice.DUE);
        assertThat(dec.getCarriedInTotal()).isEqualByComparingTo("630");
        assertThat(dec.getTotal()).isEqualByComparingTo("2130");
        assertThat(nov.getCarriedIntoId()).isEqualTo("dec");
        assertThat(savedLines).extracting(MonthlyInvoiceLine::getLabel).contains("Carried forward from November 2026 (HIN-202611-0001)");
    }

    @Test
    void nothingToBillIssuesNothing() {
        when(fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(eq("b1"), any(), any())).thenReturn(List.of());
        assertThat(service.issue(business("QA Lagos", null), YearMonth.of(2026, 11), ISSUED, null)).isNull();
        verify(invoices, never()).save(any());
    }

    @Test
    void vatIsAddedOnlyWhenTheSettingSaysSo() {
        FeeSettings d = FeeSettings.DEFAULTS;
        when(settings.fees()).thenReturn(new FeeSettings(d.model(), d.perRoom(), d.revenuePct(), d.trialDays(), d.dueDay(), d.graceDays(), d.marketerSharePct(),
                d.marketerMonths(), d.whtPct(), d.providerFeePct(), d.hmsSharePct(), d.marketerMinPayout(), new BigDecimal("7.5"), d.invoiceMinimum()));
        when(fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(eq("b1"), any(), any()))
                .thenReturn(List.of(day("h1", "Ikeja", 1, "1000000", "15000")));
        MonthlyInvoice inv = service.issue(business("QA Lagos", null), YearMonth.of(2026, 11), ISSUED, null);
        assertThat(inv.getVatAmount()).isEqualByComparingTo("1125");
        assertThat(inv.getTotal()).isEqualByComparingTo("16125");
    }

    // ---- the staging guard ----------------------------------------------------------------------------------

    @Test
    void onStagingOnlyQaBusinessesAreBilled() {
        MonthlyInvoiceService staging = service(true);
        assertThat(staging.billable(business("Royal Suites Ltd", "godwin@royalsuites.ng"))).isFalse();
        assertThat(staging.billable(business("THEWISEMAN", "owner@gmail.com"))).isFalse();
        assertThat(staging.billable(business("QA Referred Hotel uuxcq", "x@gmail.com"))).isTrue();
        assertThat(staging.billable(business("Some Hotel", "owner@hospio-qa.test"))).isTrue();
        assertThat(service(false).billable(business("Royal Suites Ltd", "godwin@royalsuites.ng"))).as("production bills everyone").isTrue();
        when(tenant.business("b1")).thenReturn(Optional.of(business("Royal Suites Ltd", "godwin@royalsuites.ng")));
        assertThatThrownBy(() -> staging.issueOne("b1", YearMonth.of(2026, 11), ISSUED)).hasMessageContaining("only invoices QA businesses");
    }

    @Test
    void aNoticeGoesOncePerKindAndDayAndNeverToANonQaBusinessOnStaging() {
        MonthlyInvoice i = invoice(ISSUED);
        i.setId("inv1");
        i.setBusinessName("Royal Suites Ltd");
        when(tenant.business("b1")).thenReturn(Optional.of(business("Royal Suites Ltd", "godwin@royalsuites.ng")));
        assertThat(service(true).sendNotice(i, MonthlyInvoiceEvent.REMINDER, LocalDate.of(2026, 12, 6))).isTrue();
        verify(deliveries, never()).send(any(List.class));
        ArgumentCaptor<MonthlyInvoiceEvent> logged = ArgumentCaptor.forClass(MonthlyInvoiceEvent.class);
        verify(events).save(logged.capture());
        assertThat(logged.getValue().getOutcome()).isEqualTo("SKIPPED_QA_ONLY");

        when(events.existsByInvoiceIdAndKindAndOnDate("inv1", MonthlyInvoiceEvent.REMINDER, LocalDate.of(2026, 12, 6))).thenReturn(true);
        assertThat(service.sendNotice(i, MonthlyInvoiceEvent.REMINDER, LocalDate.of(2026, 12, 6))).isFalse();
    }

    @Test
    void theFinalWarningSaysExactlyWhatPausesAndWhen() {
        MonthlyInvoice i = invoice(ISSUED);
        i.setId("inv1");
        MonthlyInvoiceService.Notice n = service.noticeText(i, MonthlyInvoiceEvent.FINAL_WARNING, business("QA Lagos", "ada@hospio-qa.test"));
        assertThat(n.text()).contains("from 12 December 2026 at 06:00, card/transfer POS payments, new sales and new bookings pause until it is paid")
                .contains("Pay now: https://web-staging.hospio360.com/dashboard/billing?invoice=inv1").contains("Oyeks Global Ltd · RC 10001123 · Lagos, Nigeria")
                .doesNotContainIgnoringCase("debit");
        assertThat(n.emailKind()).isEqualTo(com.hospio.common.delivery.Delivery.Kind.INVOICE_FINAL);
    }

    @Test
    void aCreditNoteIsALineOnTheInvoiceAndLowersTheTotal() {
        MonthlyInvoice i = invoice(ISSUED);
        i.setId("inv1");
        i.setTotal(new BigDecimal("12000.00"));
        when(invoices.lockById("inv1")).thenReturn(Optional.of(i));
        when(invoices.findById("inv1")).thenReturn(Optional.of(i));
        com.hospio.common.security.HospioUserDetails admin = new com.hospio.common.security.HospioUserDetails("a2", "tobi@hospio360.com", "Tobi Lawal", null,
                List.of(), java.util.Map.of(com.hospio.common.enums.Role.FINANCE_ADMIN, com.hospio.common.enums.AccessLevel.FULL), com.hospio.common.security.JwtUtil.SCOPE_PLATFORM);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        try {
            service.adjust("inv1", new MonthlyInvoiceService.AdjustRequest(new BigDecimal("-3000"), "Sales over-recorded"));
        } catch (RuntimeException ignored) {
            // the view after it needs more mocks; the line and totals are what is checked here
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(savedLines).hasSize(1);
        assertThat(savedLines.get(0).getInvoiceId()).isEqualTo("inv1");
        assertThat(savedLines.get(0).getCreatedByName()).isEqualTo("Tobi Lawal");
        assertThat(savedLines.get(0).getSection()).isEqualTo(MonthlyInvoiceLine.ADJUSTMENT);
        assertThat(i.getTotal()).isEqualByComparingTo("9000");
        assertThat(i.getAdjustmentsTotal()).isEqualByComparingTo("-3000");
    }
}
