package com.hospio.messaging.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.messaging.client.TenantClient;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.service.NotificationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** docs/guest-message-billing.md: what is billable, who may send, prices, and the monthly invoice. */
class CommBillingTest {

    private final CommPlanRepository plans = mock(CommPlanRepository.class);
    private final CommPricesRepository prices = mock(CommPricesRepository.class);
    private final CommInvoiceRepository invoices = mock(CommInvoiceRepository.class);
    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final TenantClient tenants = mock(TenantClient.class);
    private final NotificationService notices = mock(NotificationService.class);
    private final com.hospio.messaging.mail.MailDispatcher mail = mock(com.hospio.messaging.mail.MailDispatcher.class);
    private final com.hospio.messaging.client.IdentityClient identity = mock(com.hospio.messaging.client.IdentityClient.class);
    private CommBilling billing;

    @BeforeEach
    void setUp() {
        CommPrices d = new CommPrices();
        d.setId("DEFAULT");
        d.setEmailPrice(new BigDecimal("5.00"));
        d.setSmsPrice(new BigDecimal("10.00"));
        d.setWhatsappPrice(new BigDecimal("25.00"));
        when(prices.findById("DEFAULT")).thenReturn(Optional.of(d));
        when(tenants.hotel(anyString())).thenReturn(Optional.of(new TenantClient.Sender("Simpas Ikeja", "simpas@hotel.test")));
        when(tenants.business(anyString())).thenReturn(Optional.of(new TenantClient.Sender("Royal Suites Ltd", "owner@hotel.test")));
        billing = new CommBilling(plans, prices, invoices, deliveries, tenants, notices, mock(PaystackClient.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), mail, "https://web.example");
        billing.setIdentity(identity);
    }

    private CommPlan plan(boolean enabled, Instant suspended) {
        return CommPlan.builder().businessId("b1").hotelId("h1").enabled(enabled).suspendedAt(suspended).suspendedReason("invoice MSG-1 unpaid").build();
    }

    @Test
    void codesSignInAndStaffMessagesAreFreeAndAlwaysGo() {
        for (String kind : List.of("PHONE_CODE", "TEMP_PASSWORD", "INVITE", "PASSWORD_RESET", "NOTICE", "HANDOVER_PACK", "INVOICE")) {
            assertThat(billing.gate("h1", kind)).as(kind).isEqualTo(CommBilling.Gate.FREE);
        }
        verify(plans, never()).findByHotelId(anyString());
    }

    @Test
    void aHotelThatIsOffOrSuspendedDoesNotSendBillableMessages() {
        when(plans.findByHotelId("h1")).thenReturn(Optional.empty());
        assertThat(billing.gate("h1", "BOOKING_CONFIRMATION")).extracting(CommBilling.Gate::billable, CommBilling.Gate::allowed).containsExactly(true, false);
        when(plans.findByHotelId("h1")).thenReturn(Optional.of(plan(false, null)));
        assertThat(billing.gate("h1", "RECEIPT").allowed()).isFalse();
        when(plans.findByHotelId("h1")).thenReturn(Optional.of(plan(true, Instant.now())));
        assertThat(billing.gate("h1", "PURCHASE_ORDER").reason()).contains("suspended").contains("MSG-1");
        when(plans.findByHotelId("h1")).thenReturn(Optional.of(plan(true, null)));
        assertThat(billing.gate("h1", "MESSAGE")).isEqualTo(new CommBilling.Gate(true, true, null));
    }

    @Test
    void aHotelsOwnPricesOverrideTheDefaults() {
        when(plans.findByHotelId("h1")).thenReturn(Optional.of(CommPlan.builder().hotelId("h1").enabled(true).smsPrice(new BigDecimal("8.00")).build()));
        assertThat(billing.price("h1", "SMS")).isEqualByComparingTo("8");
        assertThat(billing.price("h1", "WHATSAPP")).isEqualByComparingTo("25");
        assertThat(billing.price("h1", "EMAIL")).isEqualByComparingTo("5");
    }

    @Test
    void aMonthsMessagesBecomeOneInvoiceDueInTenDays() {
        when(deliveries.unbilledUsage(eq("h1"), any(), any(), any())).thenReturn(List.of(
                new Object[] {"EMAIL", 12L, new BigDecimal("60.00")},
                new Object[] {"SMS", 3L, new BigDecimal("30.00")},
                new Object[] {"WHATSAPP", 2L, new BigDecimal("50.00")}));
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        when(invoices.countByMonth("2026-11")).thenReturn(4L);
        when(invoices.save(any())).thenAnswer(i -> {
            CommInvoice inv = i.getArgument(0);
            inv.setId("inv-1");
            return inv;
        });
        CommInvoice inv = billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        assertThat(inv.getNumber()).isEqualTo("MSG-202611-0005");
        assertThat(inv.getEmailCount()).isEqualTo(12);
        assertThat(inv.getTotalAmount()).isEqualByComparingTo("140");
        assertThat(inv.getStatus()).isEqualTo(CommInvoice.Status.DUE);
        assertThat(inv.getDueOn()).isEqualTo(java.time.LocalDate.now(CommBilling.LAGOS).plusDays(10));
        assertThat(inv.getHotelName()).isEqualTo("Simpas Ikeja");
        verify(deliveries).attachToInvoice(eq("inv-1"), eq("h1"), eq(CommBilling.CHARGED), any(), any());
    }

    @Test
    void nothingIsIssuedForAQuietMonthOrTwiceForOneMonth() {
        when(deliveries.unbilledUsage(eq("h1"), any(), any(), any())).thenReturn(List.of());
        assertThat(billing.issue("h1", "b1", YearMonth.of(2026, 10))).isEmpty();
        when(deliveries.unbilledUsage(eq("h1"), any(), any(), any())).thenReturn(List.<Object[]>of(new Object[] {"SMS", 1L, new BigDecimal("10.00")}));
        when(invoices.findByHotelIdAndMonth("h1", "2026-10")).thenReturn(Optional.of(new CommInvoice()));
        assertThat(billing.issue("h1", "b1", YearMonth.of(2026, 10))).isEmpty();
        verify(invoices, never()).save(any());
    }

    @Test
    void onlyMessagesThatWentOutAreCharged() {
        assertThat(CommBilling.CHARGED).containsExactlyInAnyOrder(DeliveryRecord.Status.SENT, DeliveryRecord.Status.DELIVERED);
        assertThat(CommBilling.label("BOOKING_CONFIRMATION")).isEqualTo("Booking confirmations");
        assertThat(CommBilling.naira(new BigDecimal("1250.50"))).isEqualTo("₦1,250.50");
        assertThat(CommBilling.monthName("2026-11")).isEqualTo("November 2026");
    }

    @Test
    void anIssuedInvoiceReachesTheOwnersAndManagersEvenWithoutAHotelEmail() {
        when(tenants.hotel(anyString())).thenReturn(Optional.of(new TenantClient.Sender("Simpas Ikeja", null)));
        when(tenants.business(anyString())).thenReturn(Optional.of(new TenantClient.Sender("Royal Suites Ltd", null)));
        when(identity.withRole("h1", "OWNER")).thenReturn(List.of(contact("godwin@royal.test")));
        when(identity.withRole("h1", "MANAGER")).thenReturn(List.of(contact("tunde@royal.test"), contact("GODWIN@royal.test")));
        when(deliveries.unbilledUsage(eq("h1"), any(), any(), any())).thenReturn(List.<Object[]>of(new Object[] {"EMAIL", 3L, new BigDecimal("15.00")}));
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        when(invoices.save(any())).thenAnswer(i -> {
            CommInvoice inv = i.getArgument(0);
            inv.setId("a8ff37d7-617d-4f3d-9831-9cffa070bf4f");
            return inv;
        });
        billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        org.mockito.ArgumentCaptor<com.hospio.common.kafka.events.DeliveryRequestedEvent> sent = org.mockito.ArgumentCaptor.forClass(com.hospio.common.kafka.events.DeliveryRequestedEvent.class);
        verify(mail, org.mockito.Mockito.times(2)).accept(sent.capture());
        assertThat(sent.getAllValues()).extracting(com.hospio.common.kafka.events.DeliveryRequestedEvent::getTo).containsExactly("godwin@royal.test", "tunde@royal.test");
        assertThat(sent.getAllValues()).allSatisfy(e -> assertThat(e.getKind()).isEqualTo("INVOICE"));
        org.mockito.ArgumentCaptor<String> eventId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notices).notifyRoles(eventId.capture(), eq("COMM_INVOICE"), anyString(), anyString(), anyString(), eq("OWNER"), eq("MANAGER"));
        assertThat(eventId.getValue()).hasSize(36);
    }

    private static com.hospio.messaging.client.IdentityClient.Contact contact(String email) {
        return new com.hospio.messaging.client.IdentityClient.Contact("u", "Someone", email, null, "b1", "ACTIVE", List.of());
    }
}
