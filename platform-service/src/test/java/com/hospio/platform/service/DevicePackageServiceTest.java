package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.billing.FeeDebitDtos;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.DeviceInvoice;
import com.hospio.platform.entity.DevicePackage;
import com.hospio.platform.entity.DevicePayment;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.DeviceCatalogueRepository;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.DevicePackageItemRepository;
import com.hospio.platform.repository.DevicePackageRepository;
import com.hospio.platform.repository.DevicePaymentRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device packages (docs/plan-device-packages.md): what a package may hold, the device line of a day (capped at what is left), the
 * platform fee paid before devices, a debit's device share credited once, and paid off at ₦0.
 */
class DevicePackageServiceTest {

    private final DevicePackageRepository packages = mock(DevicePackageRepository.class);
    private final DevicePackageItemRepository items = mock(DevicePackageItemRepository.class);
    private final DevicePaymentRepository payments = mock(DevicePaymentRepository.class);
    private final DailyFeeRepository fees = mock(DailyFeeRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final DeviceInvoiceRepository invoices = mock(DeviceInvoiceRepository.class);
    private final DevicePackageService service = new DevicePackageService(mock(DeviceCatalogueRepository.class), packages, items, payments, fees, tenant, tx,
            invoices);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Lagos"));

    @BeforeEach
    void signIn() {
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria Nwosu", null, List.of(), Map.of(Role.SUPER_ADMIN,
                com.hospio.common.enums.AccessLevel.EDIT), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(tenant.business("b1")).thenReturn(Optional.of(new TenantClient.Business("b1", "Royal Suites", "Lagos", "LIVE", null, null, null, null, null,
                "PERCENT_OF_SALES", new BigDecimal("1.5"), null, null, null, null, 1, List.of(new TenantClient.Hotel("h1", "Simpas Ikeja", "Lagos", 120, "ACTIVE", null)))));
        when(packages.save(any())).thenAnswer(inv -> {
            DevicePackage p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId("pk1");
            }
            return p;
        });
        when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static DevicePackage installed(String option, String total, String paid) {
        DevicePackage p = DevicePackage.builder().businessId("b1").hotelId("h1").status(DevicePackage.INSTALLED).paymentOption(option)
                .deviceRate(new BigDecimal("2.5")).total(new BigDecimal(total)).paid(new BigDecimal(paid)).installedAt(TODAY.minusDays(5)).build();
        p.setId("pk1");
        return p;
    }

    private static DevicePackageService.ItemRequest tablet(int n) {
        return new DevicePackageService.ItemRequest("dc1", "Tablet", "ONE_OFF", n, new BigDecimal("180000"));
    }

    // ---- what a package may hold ----------------------------------------------------------------------

    @Test
    void oneOffItemsMakeTheTotalAndADataPlanTheMonthlyCharge() {
        DevicePackageService.PackageView v = service.save("b1", "h1", new DevicePackageService.SaveRequest("TRANSACTION",
                List.of(tablet(2), new DevicePackageService.ItemRequest("dc2", "Router", "ONE_OFF", 1, new BigDecimal("65000")),
                        new DevicePackageService.ItemRequest("dc4", "Data plan", "MONTHLY", 1, new BigDecimal("30000"))), null, null, null));
        assertThat(v.total()).isEqualByComparingTo("425000");
        assertThat(v.dataPlanMonthly()).isEqualByComparingTo("30000");
        assertThat(v.deviceRate()).as("2.5 % unless agreed otherwise").isEqualByComparingTo("2.5");
        assertThat(v.status()).isEqualTo(DevicePackage.DRAFT);
        assertThat(v.hotelName()).isEqualTo("Simpas Ikeja");
    }

    @Test
    void monthlyIsOneToSixMonthsAndOwnDevicesSupplyNothing() {
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("MONTHLY", List.of(tablet(1)), null, 7, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Monthly payment is over 1 to 6 months");
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("MONTHLY", List.of(tablet(1)), null, 0, null)))
                .hasMessage("Monthly payment is over 1 to 6 months");
        assertThat(service.save("b1", "h1", new DevicePackageService.SaveRequest("MONTHLY", List.of(tablet(1)), null, 3, null)).monthlyInstalment())
                .isEqualByComparingTo("60000");
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("OWN_DEVICES", List.of(tablet(1)), null, null, null)))
                .hasMessageContaining("Own devices means nothing is supplied");
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("TRANSACTION", List.of(), null, null, null)))
                .hasMessageContaining("Add the devices supplied");
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("SOMETHING", List.of(tablet(1)), null, null, null)))
                .hasMessageContaining("Choose how the devices are paid for");
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("TRANSACTION", List.of(tablet(1)), new BigDecimal("25"), null, null)))
                .hasMessageContaining("at most 20");
        assertThatThrownBy(() -> service.save("b1", "nope", new DevicePackageService.SaveRequest("ONE_TIME", List.of(tablet(1)), null, null, null)))
                .hasMessageContaining("isn't one of this business's");
    }

    @Test
    void anInstalledPackageCanNotBeChanged() {
        when(packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc("h1", DevicePackage.CANCELLED))
                .thenReturn(Optional.of(installed("TRANSACTION", "425000", "0")));
        assertThatThrownBy(() -> service.save("b1", "h1", new DevicePackageService.SaveRequest("ONE_TIME", List.of(tablet(1)), null, null, null)))
                .hasMessageContaining("is installed: it can't be changed");
    }

    // ---- the device line of a day ---------------------------------------------------------------------

    @Test
    void theDaysDeviceFeeIsTheRateOfSalesCappedAtWhatIsLeft() {
        DevicePackage p = installed("TRANSACTION", "425000", "0");
        when(packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc("h1", DevicePackage.CANCELLED)).thenReturn(Optional.of(p));
        when(fees.findByHotelIdAndStatusInAndFeeDateBefore(eq("h1"), any(), any())).thenReturn(List.of());

        DevicePackageService.DeviceLine line = service.lineFor("h1", TODAY, new BigDecimal("133767.48"));
        assertThat(line.packageId()).isEqualTo("pk1");
        assertThat(line.fee()).as("2.5 % of the day's sales").isEqualByComparingTo("3344.19");

        // 424,900 is already owed on open days: only 100 is left to ask for.
        DailyFee open = DailyFee.builder().devicePackageId("pk1").fee(BigDecimal.ZERO).carriedIn(BigDecimal.ZERO).deviceFee(new BigDecimal("424900"))
                .due(new BigDecimal("424900")).status(DailyFee.UNPAID).build();
        when(fees.findByHotelIdAndStatusInAndFeeDateBefore(eq("h1"), any(), any())).thenReturn(List.of(open));
        assertThat(service.lineFor("h1", TODAY, new BigDecimal("133767.48")).fee()).isEqualByComparingTo("100");
    }

    @Test
    void noDeviceLineBeforeInstallationForOtherOptionsOrOncePaidOff() {
        DevicePackage p = installed("TRANSACTION", "425000", "0");
        when(packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc("h1", DevicePackage.CANCELLED)).thenReturn(Optional.of(p));
        assertThat(service.lineFor("h1", TODAY.minusDays(6), new BigDecimal("100000")).packageId()).as("before installation").isNull();
        p.setPaymentOption(DevicePackage.MONTHLY);
        assertThat(service.lineFor("h1", TODAY, new BigDecimal("100000")).fee()).as("monthly is invoiced, not taken from sales").isZero();
        p.setPaymentOption(DevicePackage.TRANSACTION);
        p.setPaid(new BigDecimal("425000"));
        p.setStatus(DevicePackage.PAID_OFF);
        assertThat(service.lineFor("h1", TODAY, new BigDecimal("100000")).fee()).as("paid off").isZero();
    }

    // ---- the platform fee first ----------------------------------------------------------------------

    @Test
    void aPartDebitPaysThePlatformFeeFirst() {
        DailyFee f = DailyFee.builder().fee(new BigDecimal("2006.51")).carriedIn(new BigDecimal("100")).deviceFee(new BigDecimal("3344.19"))
                .deviceCarriedIn(BigDecimal.ZERO).due(new BigDecimal("5450.70")).collected(new BigDecimal("3000")).build();
        assertThat(f.platformPaid()).isEqualByComparingTo("2106.51");
        assertThat(f.platformRemaining()).isZero();
        assertThat(f.devicePaid()).isEqualByComparingTo("893.49");
        assertThat(f.deviceRemaining()).isEqualByComparingTo("2450.70");
        f.setCollected(new BigDecimal("1000"));
        assertThat(f.devicePaid()).as("nothing reaches devices until the platform fee is paid").isZero();
        assertThat(f.platformRemaining()).isEqualByComparingTo("1106.51");
    }

    @Test
    void theDebitsDeviceShareIsCreditedOnceAndPaysThePackageOff() {
        DevicePackage p = installed("TRANSACTION", "1000", "900");
        when(packages.lockById("pk1")).thenReturn(Optional.of(p));
        DailyFee f = DailyFee.builder().businessId("b1").hotelId("h1").devicePackageId("pk1").feeDate(TODAY.minusDays(1)).build();
        f.setId("df1");

        service.creditTransaction(f, new BigDecimal("150"), "HF-1");
        assertThat(p.getPaid()).as("never more than is left").isEqualByComparingTo("1000");
        assertThat(p.getStatus()).isEqualTo(DevicePackage.PAID_OFF);

        when(payments.existsByPackageIdAndSourceAndReference("pk1", DevicePayment.TRANSACTION, "HF-1")).thenReturn(true);
        service.creditTransaction(f, new BigDecimal("150"), "HF-1");
        verify(payments, times(1)).save(any());
    }

    @Test
    void theMorningDebitSendsItsDeviceShareToThePackage() {
        DailyFeeRepository dailyFees = mock(DailyFeeRepository.class);
        FeeCollectionRepository collections = mock(FeeCollectionRepository.class);
        FeeDebitClient debits = mock(FeeDebitClient.class);
        DevicePackageService devices = mock(DevicePackageService.class);
        FeeDebitRunner runner = new FeeDebitRunner(dailyFees, collections, debits, tx, devices);
        when(collections.findByHotelId("a0000000-0000-4000-8000-000000000001")).thenReturn(Optional.of(FeeCollection.builder().hotelId("a0000000-0000-4000-8000-000000000001").provider("OPAY").debitEnabled(true).build()));
        DailyFee f = DailyFee.builder().businessId("b1").hotelId("a0000000-0000-4000-8000-000000000001").feeDate(TODAY.minusDays(1)).fee(new BigDecimal("2000")).carriedIn(BigDecimal.ZERO)
                .deviceFee(new BigDecimal("3000")).deviceCarriedIn(BigDecimal.ZERO).due(new BigDecimal("5000")).devicePackageId("pk1")
                .status(DailyFee.SCHEDULED).build();
        f.setId("df1");
        when(dailyFees.findById("df1")).thenReturn(Optional.of(f));
        when(debits.debit(eq("a0000000-0000-4000-8000-000000000001"), eq("OPAY"), any(), anyString(), anyString())).thenReturn(new FeeDebitDtos.DebitResult("APPROVED", new BigDecimal("2500"), "OP1", null));

        assertThat(runner.step(f)).isEqualTo(DailyFee.PART_PAID);

        verify(devices).creditTransaction(eq(f), eq(new BigDecimal("500.00")), anyString());
    }

    @Test
    void aFailedDebitCreditsNothing() {
        DailyFeeRepository dailyFees = mock(DailyFeeRepository.class);
        FeeCollectionRepository collections = mock(FeeCollectionRepository.class);
        FeeDebitClient debits = mock(FeeDebitClient.class);
        DevicePackageService devices = mock(DevicePackageService.class);
        FeeDebitRunner runner = new FeeDebitRunner(dailyFees, collections, debits, tx, devices);
        when(collections.findByHotelId("a0000000-0000-4000-8000-000000000001")).thenReturn(Optional.of(FeeCollection.builder().hotelId("a0000000-0000-4000-8000-000000000001").provider("OPAY").debitEnabled(true).build()));
        DailyFee f = DailyFee.builder().businessId("b1").hotelId("a0000000-0000-4000-8000-000000000001").feeDate(TODAY.minusDays(1)).fee(new BigDecimal("2000")).carriedIn(BigDecimal.ZERO)
                .deviceFee(new BigDecimal("3000")).deviceCarriedIn(BigDecimal.ZERO).due(new BigDecimal("5000")).devicePackageId("pk1")
                .status(DailyFee.SCHEDULED).build();
        f.setId("df1");
        when(dailyFees.findById("df1")).thenReturn(Optional.of(f));
        when(debits.debit(eq("a0000000-0000-4000-8000-000000000001"), eq("OPAY"), any(), anyString(), anyString())).thenReturn(new FeeDebitDtos.DebitResult("FAILED", BigDecimal.ZERO, null, "insufficient balance"));

        assertThat(runner.step(f)).isEqualTo(DailyFee.UNPAID);
        verify(devices, never()).creditTransaction(any(), any(), any());
    }

    // ---- payments by hand ---------------------------------------------------------------------------

    @Test
    void aOneTimePaymentPaysThePackageOffAndNeverMoreThanIsLeft() {
        DevicePackage p = installed("ONE_TIME", "425000", "0");
        when(packages.lockById("pk1")).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> service.recordPayment("pk1", new DevicePackageService.PaymentRequest(new BigDecimal("500000"), "TRF-1", null, null)))
                .hasMessageContaining("more than is left");
        service.recordPayment("pk1", new DevicePackageService.PaymentRequest(new BigDecimal("425000"), "TRF-1", null, "Paid at installation"));
        assertThat(p.getStatus()).isEqualTo(DevicePackage.PAID_OFF);
        assertThat(p.remaining()).isZero();
    }

    @Test
    void aPackageWithMoneyOnItCanNotBeCancelled() {
        when(packages.lockById("pk1")).thenReturn(Optional.of(installed("TRANSACTION", "425000", "1000")));
        assertThatThrownBy(() -> service.cancel("pk1")).hasMessageContaining("can't be cancelled");
    }

    // ---- the data plan after installation ---------------------------------------------------------------

    private DevicePackage withPlan(LocalDate installed) {
        DevicePackage p = installed("TRANSACTION", "425000", "0");
        p.setInstalledAt(installed);
        p.setDataPlanMonthly(new BigDecimal("30000"));
        when(packages.lockById("pk1")).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void stoppingTheDataPlanStopsBillingFromTheNextDueDayAndStartingResumesFromTheNextOne() {
        DevicePackage p = withPlan(TODAY.minusDays(40));
        DevicePackageService.PackageView v = service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("STOP", null));
        LocalDate next = TODAY.minusDays(40).plusMonths(2);
        assertThat(p.getDataPlanStoppedOn()).isEqualTo(next.isAfter(TODAY) ? next : TODAY.minusDays(40).plusMonths(3));
        assertThat(v.dataPlanRunning()).isFalse();
        assertThat(p.dataPlanBills(TODAY.minusDays(40).plusMonths(1))).as("already invoiced months stay").isTrue();
        assertThat(p.dataPlanBills(p.getDataPlanStoppedOn())).isFalse();
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("STOP", null))).hasMessageContaining("isn't running");
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("PRICE", new BigDecimal("35000"))))
                .hasMessageContaining("Start the data plan");

        service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("START", new BigDecimal("35000")));
        assertThat(p.dataPlanRunning()).isTrue();
        assertThat(p.getDataPlanMonthly()).isEqualByComparingTo("35000");
        assertThat(p.getDataPlanFrom()).isAfterOrEqualTo(TODAY);
        assertThat(p.dataPlanBills(TODAY.minusDays(40).plusMonths(1))).as("the months it was stopped are never billed").isFalse();
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("START", null))).hasMessageContaining("already running");
    }

    @Test
    void repricingAndItsRules() {
        DevicePackage p = withPlan(TODAY.minusDays(10));
        service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("PRICE", new BigDecimal("40000")));
        assertThat(p.getDataPlanMonthly()).isEqualByComparingTo("40000");
        assertThat(p.getDataPlanChangedByName()).isEqualTo("Amoria Nwosu");
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("PRICE", BigDecimal.ZERO))).hasMessageContaining("monthly price");
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("PRICE", new BigDecimal("-5")))).hasMessageContaining("between ₦0");
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("PAUSE", null))).hasMessageContaining("STOP, START or PRICE");
        p.setStatus(DevicePackage.DRAFT);
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("STOP", null))).hasMessageContaining("until it is installed");
        p.setStatus(DevicePackage.CANCELLED);
        assertThatThrownBy(() -> service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("STOP", null))).hasMessageContaining("cancelled");
        p.setStatus(DevicePackage.PAID_OFF);
        service.setDataPlan("pk1", new DevicePackageService.DataPlanRequest("STOP", null));
        assertThat(p.dataPlanRunning()).as("a paid-off package's data plan can still be stopped").isFalse();
    }

    @Test
    void renewalsListTheSubscriptionsToRenewMostUrgentFirst() {
        DevicePackage soon = withPlan(TODAY.minusDays(10));
        soon.setDataPlanRenewedThrough(TODAY.plusDays(3));
        DevicePackage never = installed("ONE_TIME", "65000", "65000");
        never.setId("pk2");
        never.setStatus(DevicePackage.PAID_OFF);
        never.setDataPlanMonthly(new BigDecimal("30000"));
        DevicePackage later = installed("ONE_TIME", "65000", "65000");
        later.setId("pk3");
        later.setDataPlanMonthly(new BigDecimal("30000"));
        later.setDataPlanRenewedThrough(TODAY.plusDays(25));
        DevicePackage stopped = installed("ONE_TIME", "65000", "65000");
        stopped.setId("pk4");
        stopped.setDataPlanMonthly(new BigDecimal("30000"));
        stopped.setDataPlanStoppedOn(TODAY);
        when(packages.findByStatusInOrderByUpdatedAtDesc(any())).thenReturn(List.of(soon, never, later, stopped));

        List<DevicePackageService.RenewalView> r = service.dataPlanRenewals(7);

        assertThat(r).extracting(DevicePackageService.RenewalView::packageId).containsExactly("pk2", "pk1");
        assertThat(r.get(1).daysLeft()).isEqualTo(3);
        assertThat(service.dataPlanRenewals(30)).extracting(DevicePackageService.RenewalView::packageId).containsExactly("pk2", "pk1", "pk3");

        service.markDataPlanRenewed("pk1", new DevicePackageService.RenewedRequest(TODAY.plusDays(30)));
        assertThat(soon.getDataPlanRenewedThrough()).isEqualTo(TODAY.plusDays(30));
        assertThat(soon.getDataPlanRenewedByName()).isEqualTo("Amoria Nwosu");
        assertThatThrownBy(() -> service.markDataPlanRenewed("pk1", new DevicePackageService.RenewedRequest(TODAY.minusDays(1)))).hasMessageContaining("from today");
        assertThatThrownBy(() -> service.markDataPlanRenewed("pk1", new DevicePackageService.RenewedRequest(null))).hasMessageContaining("Enter the day");
    }

    @Test
    void theNextDataPlanInvoiceSkipsAMonthAlreadyInvoicedAndRenewedNeedsALivePackage() {
        DevicePackage p = withPlan(TODAY.minusMonths(2));
        p.setId("pk1");
        when(invoices.findByPackageIdOrderByDueOnDescKindAsc("pk1")).thenReturn(List.of());
        assertThat(service.nextDataPlanDue(p)).as("nothing invoiced yet: the first month").isEqualTo(TODAY.minusMonths(2));
        when(invoices.findByPackageIdOrderByDueOnDescKindAsc("pk1")).thenReturn(List.of(plan(1), plan(2), plan(3)));
        assertThat(service.nextDataPlanDue(p)).as("today's invoice is out: next month").isEqualTo(TODAY.minusMonths(2).plusMonths(3));
        when(invoices.findByPackageIdOrderByDueOnDescKindAsc("pk1")).thenReturn(List.of(plan(1), plan(2)));
        assertThat(service.nextDataPlanDue(p)).as("today's not issued yet: today").isEqualTo(TODAY.minusMonths(2).plusMonths(2));
        p.setStatus(DevicePackage.CANCELLED);
        assertThatThrownBy(() -> service.markDataPlanRenewed("pk1", new DevicePackageService.RenewedRequest(TODAY.plusDays(5))))
                .hasMessageContaining("no installed data plan");
    }

    private static DeviceInvoice plan(int sequence) {
        return DeviceInvoice.builder().packageId("pk1").kind(DeviceInvoice.DATA_PLAN).sequence(sequence).amount(new BigDecimal("30000")).build();
    }
}
