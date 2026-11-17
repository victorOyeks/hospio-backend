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

import com.hospio.common.billing.FeeDebitDtos;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.client.PlatformPaystackClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DeviceInvoice;
import com.hospio.platform.entity.DevicePackage;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.DevicePackageRepository;
import com.hospio.platform.repository.DevicePaymentRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * Monthly device invoices (docs/plan-device-packages.md): instalments split to the kobo, issued on their day and never more than is
 * left, debited once a day, overdue steps at 3, 7 and 14 days, the POS pause lifted on payment unless the daily fee holds it.
 */
class DeviceInvoiceServiceTest {

    static final String HOTEL = "a0000000-0000-4000-8000-000000000001";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Lagos"));

    private final DeviceInvoiceRepository invoices = mock(DeviceInvoiceRepository.class);
    private final DevicePackageRepository packages = mock(DevicePackageRepository.class);
    private final DevicePaymentRepository payments = mock(DevicePaymentRepository.class);
    private final DevicePackageService devices = mock(DevicePackageService.class);
    private final FeeCollectionRepository collections = mock(FeeCollectionRepository.class);
    private final FeeDebitClient debits = mock(FeeDebitClient.class);
    private final PlatformPaystackClient paystack = mock(PlatformPaystackClient.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final AlertService alerts = mock(AlertService.class);
    private final DeviceInvoiceService service = new DeviceInvoiceService(invoices, packages, payments, devices, collections, debits, paystack, tenant,
            mock(DeliveryService.class), alerts, new TransactionTemplate(mock(PlatformTransactionManager.class)));
    private final List<DeviceInvoice> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(invoices.save(any())).thenAnswer(inv -> {
            DeviceInvoice i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId("inv" + (saved.size() + 1));
            }
            saved.add(i);
            return i;
        });
        when(collections.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoices.findById(anyString())).thenAnswer(inv -> saved.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());
        when(invoices.lockById(anyString())).thenAnswer(inv -> saved.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static DevicePackage monthly(String total, int months, LocalDate installed) {
        DevicePackage p = DevicePackage.builder().businessId("b1").hotelId(HOTEL).hotelName("Simpas Ikeja").status(DevicePackage.INSTALLED)
                .paymentOption(DevicePackage.MONTHLY).months(months).total(new BigDecimal(total)).installedAt(installed).build();
        p.setId("pk1");
        return p;
    }

    private DeviceInvoice invoice(String status, LocalDate due) {
        DeviceInvoice i = DeviceInvoice.builder().businessId("b1").hotelId(HOTEL).packageId("pk1").hotelName("Simpas Ikeja").number("DEV-202611-0001")
                .kind(DeviceInvoice.INSTALMENT).sequence(1).ofCount(3).amount(new BigDecimal("141666.67")).dueOn(due).status(status).build();
        i.setId("inv0");
        saved.add(i);
        return i;
    }

    @Test
    void instalmentsAreSplitToTheKoboAndTheLastTakesTheRest() {
        DevicePackage p = monthly("425000", 3, TODAY);
        assertThat(DeviceInvoiceService.instalment(p, 1)).isEqualByComparingTo("141666.67");
        assertThat(DeviceInvoiceService.instalment(p, 2)).isEqualByComparingTo("141666.67");
        assertThat(DeviceInvoiceService.instalment(p, 3)).isEqualByComparingTo("141666.66");
    }

    @Test
    void instalmentsAreIssuedOnTheirDayOnly() {
        // Installed 40 days ago over 3 months: instalments 1 (day 0) and 2 (month 1) are due; 3 is not yet.
        DevicePackage p = monthly("300000", 3, TODAY.minusDays(40));
        p.setDataPlanMonthly(new BigDecimal("30000"));
        when(packages.findByStatusInOrderByUpdatedAtDesc(any())).thenReturn(List.of(p));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of());

        int[] r = service.issue(TODAY);

        assertThat(saved).extracting(DeviceInvoice::getKind, DeviceInvoice::getSequence)
                .contains(org.assertj.core.groups.Tuple.tuple(DeviceInvoice.INSTALMENT, 1), org.assertj.core.groups.Tuple.tuple(DeviceInvoice.INSTALMENT, 2),
                        org.assertj.core.groups.Tuple.tuple(DeviceInvoice.DATA_PLAN, 1), org.assertj.core.groups.Tuple.tuple(DeviceInvoice.DATA_PLAN, 2));
        assertThat(saved).noneMatch(i -> DeviceInvoice.INSTALMENT.equals(i.getKind()) && i.getSequence() == 3);
        assertThat(r[0]).isEqualTo(4);
        assertThat(saved.get(0).getNumber()).startsWith("DEV-");
    }

    @Test
    void anInstalmentNeverAsksForMoreThanIsLeft() {
        // 280,000 of 300,000 was paid by hand: the first instalment asks for 20,000, not 100,000.
        DevicePackage p = monthly("300000", 3, TODAY);
        p.setPaid(new BigDecimal("280000"));
        when(packages.findByStatusInOrderByUpdatedAtDesc(any())).thenReturn(List.of(p));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of());
        service.issue(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo("20000");
    }

    @Test
    void aPaidOffPackagesOpenInstalmentsAreVoided() {
        DevicePackage p = monthly("300000", 3, TODAY.minusDays(40));
        p.setStatus(DevicePackage.PAID_OFF);
        DeviceInvoice open = invoice(DeviceInvoice.DUE, TODAY.minusDays(10));
        when(packages.findByStatusInOrderByUpdatedAtDesc(any())).thenReturn(List.of(p));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of(open));
        assertThat(service.issue(TODAY)[1]).isEqualTo(1);
        assertThat(open.getStatus()).isEqualTo(DeviceInvoice.VOID);
    }

    @Test
    void anOpenInvoiceIsDebitedOnceADayAndAnApprovedDebitPaysIt() {
        DeviceInvoice i = invoice(DeviceInvoice.DUE, TODAY);
        when(collections.findByHotelId(HOTEL)).thenReturn(Optional.of(FeeCollection.builder().hotelId(HOTEL).provider("OPAY").debitEnabled(true).build()));
        when(debits.debit(eq(HOTEL), eq("OPAY"), any(), anyString(), anyString())).thenReturn(new FeeDebitDtos.DebitResult("APPROVED", new BigDecimal("141666.67"), "OP1", null));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of());
        when(packages.lockById("pk1")).thenReturn(Optional.of(monthly("425000", 3, TODAY)));

        assertThat(service.debit(i, TODAY)).isEqualTo("PAID");
        assertThat(i.getStatus()).isEqualTo(DeviceInvoice.PAID);
        assertThat(i.getPaidVia()).isEqualTo("DEBIT");
        verify(devices).credit(any(), eq(new BigDecimal("141666.67")));

        DeviceInvoice j = invoice(DeviceInvoice.DUE, TODAY);
        j.setLastDebitOn(TODAY);
        assertThat(service.debit(j, TODAY)).as("already tried today").isNull();
    }

    @Test
    void aFailedDebitKeepsTheInvoiceOpenWithTheReasonAndNothingIsTriedWithTheDebitOff() {
        DeviceInvoice i = invoice(DeviceInvoice.DUE, TODAY);
        when(collections.findByHotelId(HOTEL)).thenReturn(Optional.of(FeeCollection.builder().hotelId(HOTEL).provider("OPAY").debitEnabled(true).build()));
        when(debits.debit(eq(HOTEL), eq("OPAY"), any(), anyString(), anyString())).thenReturn(new FeeDebitDtos.DebitResult("FAILED", BigDecimal.ZERO, null, "insufficient balance"));
        assertThat(service.debit(i, TODAY)).isEqualTo("FAILED");
        assertThat(i.getStatus()).isEqualTo(DeviceInvoice.DUE);
        assertThat(i.getLastError()).isEqualTo("insufficient balance");

        when(collections.findByHotelId(HOTEL)).thenReturn(Optional.of(FeeCollection.builder().hotelId(HOTEL).provider("OPAY").debitEnabled(false).build()));
        assertThat(service.debit(invoice(DeviceInvoice.DUE, TODAY), TODAY)).isNull();
    }

    @Test
    void overdueStepsComeAtThreeSevenAndFourteenDays() {
        DeviceInvoice at2 = invoice(DeviceInvoice.DUE, TODAY.minusDays(2));
        when(invoices.findByStatusInAndDueOnLessThanEqualOrderByDueOnAsc(any(), any())).thenReturn(List.of(at2));
        assertThat(service.overdue(TODAY)).containsExactly(1, 0, 0, 0);
        assertThat(at2.getStatus()).isEqualTo(DeviceInvoice.OVERDUE);

        DeviceInvoice at15 = invoice(DeviceInvoice.OVERDUE, TODAY.minusDays(15));
        when(invoices.findByStatusInAndDueOnLessThanEqualOrderByDueOnAsc(any(), any())).thenReturn(List.of(at15));
        FeeCollection c = FeeCollection.builder().businessId("b1").hotelId(HOTEL).build();
        when(collections.findByHotelId(HOTEL)).thenReturn(Optional.of(c));
        assertThat(service.overdue(TODAY)).containsExactly(0, 1, 1, 1);
        verify(alerts).raise(eq(DeviceInvoiceService.ALERT), eq("b1"), eq(HOTEL), anyString(), anyString(), eq("danger"));
        verify(debits).pausePos("b1", HOTEL, true);
        assertThat(c.isDevicePosPaused()).isTrue();
        // Each step happens once.
        assertThat(service.overdue(TODAY)).containsExactly(0, 0, 0, 0);
    }

    @Test
    void payingLiftsTheDevicePauseButNotTheDailyFeesOwn() {
        DeviceInvoice i = invoice(DeviceInvoice.OVERDUE, TODAY.minusDays(20));
        i.setPausedAt(java.time.Instant.now());
        FeeCollection c = FeeCollection.builder().businessId("b1").hotelId(HOTEL).devicePosPaused(true).posPaused(true).build();
        when(collections.findByHotelId(HOTEL)).thenReturn(Optional.of(c));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of());
        when(packages.lockById("pk1")).thenReturn(Optional.of(monthly("425000", 3, TODAY.minusDays(20))));
        signIn(Role.FINANCE_ADMIN, null, List.of());

        service.markPaid("inv0", "TRF-9", null);

        assertThat(i.getStatus()).isEqualTo(DeviceInvoice.PAID);
        assertThat(i.getPaidVia()).isEqualTo("MANUAL");
        assertThat(c.isDevicePosPaused()).isFalse();
        verify(debits, never()).pausePos("b1", HOTEL, false);
        verify(alerts).clear(DeviceInvoiceService.ALERT, "b1");
        assertThatThrownBy(() -> service.markPaid("inv0", "TRF-9", null)).hasMessageContaining("nothing is owed");
        assertThatThrownBy(() -> service.markPaid("inv0", " ", null)).hasMessageContaining("reference");
    }

    @Test
    void paystackConfirmsOnceAndOnlyAnotherBusinessesInvoiceIsHidden() {
        DeviceInvoice i = invoice(DeviceInvoice.DUE, TODAY);
        i.setPaystackRef("DEV-202611-0001-1765000000");
        when(invoices.findByPaystackRef("DEV-202611-0001-1765000000")).thenReturn(Optional.of(i));
        when(paystack.verify("DEV-202611-0001-1765000000")).thenReturn(new PlatformPaystackClient.Verified(true, new BigDecimal("141666.67"), "Approved"));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of());
        when(packages.lockById("pk1")).thenReturn(Optional.of(monthly("425000", 3, TODAY)));
        when(collections.findByHotelId(HOTEL)).thenReturn(Optional.empty());

        assertThat(service.confirm("DEV-202611-0001-1765000000").status()).isEqualTo(DeviceInvoice.PAID);
        assertThat(service.confirm("DEV-202611-0001-1765000000").status()).as("a second confirm changes nothing").isEqualTo(DeviceInvoice.PAID);
        verify(paystack, org.mockito.Mockito.times(1)).verify(anyString());
        assertThatThrownBy(() -> service.confirm("MSG-1")).hasMessageContaining("isn't a device invoice");

        invoice(DeviceInvoice.DUE, TODAY);
        signIn(Role.OWNER, "another-business", List.of());
        assertThatThrownBy(() -> service.payOnline("inv0", null)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static void signIn(Role role, String businessId, List<String> hotels) {
        HospioUserDetails u = new HospioUserDetails("u1", "u@x.ng", "Tobi Lawal", businessId, hotels, Map.of(role, AccessLevel.EDIT),
                businessId == null ? JwtUtil.SCOPE_PLATFORM : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test
    void aStoppedDataPlanIsNotBilledFromItsStopDayAndARestartSkipsTheGap() {
        // Installed 100 days ago: months 0..3 are due by today. Stopped from month 1, restarted from month 3.
        DevicePackage p = DevicePackage.builder().businessId("b1").hotelId(HOTEL).hotelName("Simpas Ikeja").status(DevicePackage.PAID_OFF)
                .paymentOption(DevicePackage.ONE_TIME).total(new BigDecimal("65000")).paid(new BigDecimal("65000"))
                .dataPlanMonthly(new BigDecimal("30000")).installedAt(TODAY.minusDays(100)).build();
        p.setId("pk9");
        p.setDataPlanStoppedOn(TODAY.minusDays(100).plusMonths(1));
        when(packages.findByStatusInOrderByUpdatedAtDesc(any())).thenReturn(List.of(p));
        when(invoices.findByHotelIdAndStatusIn(eq(HOTEL), any())).thenReturn(List.of());
        service.issue(TODAY);
        assertThat(saved).extracting(DeviceInvoice::getSequence).containsExactly(1);

        saved.clear();
        p.setDataPlanStoppedOn(null);
        p.setDataPlanFrom(TODAY.minusDays(100).plusMonths(3));
        when(invoices.existsByPackageIdAndKindAndSequence("pk9", DeviceInvoice.DATA_PLAN, 1)).thenReturn(true);
        service.issue(TODAY);
        assertThat(saved).extracting(DeviceInvoice::getSequence).as("months 2 and 3 were stopped").containsExactly(4);
    }
}
