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
    private final PaystackClient paystack = mock(PaystackClient.class);
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
        billing = new CommBilling(plans, prices, invoices, deliveries, tenants, notices, paystack,
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

    /** The invoice repository saves as Postgres would: an id on first save, the same object back. */
    private void invoicesSave(String id) {
        org.mockito.stubbing.Answer<CommInvoice> answer = i -> {
            CommInvoice inv = i.getArgument(0);
            if (inv.getId() == null) {
                inv.setId(id);
            }
            return inv;
        };
        when(invoices.saveAndFlush(any())).thenAnswer(answer);
        when(invoices.save(any())).thenAnswer(answer);
    }

    private void attached(String invoiceId, int count, Object[]... usage) {
        when(deliveries.attachToInvoice(eq(invoiceId), eq("h1"), eq(CommBilling.CHARGED), any())).thenReturn(count);
        when(deliveries.invoiceUsage(invoiceId)).thenReturn(List.of(usage));
    }

    @Test
    void aMonthsMessagesBecomeOneInvoiceDueInTenDays() {
        invoicesSave("inv-1");
        attached("inv-1", 17, new Object[] {"EMAIL", 12L, new BigDecimal("60.00")}, new Object[] {"SMS", 3L, new BigDecimal("30.00")},
                new Object[] {"WHATSAPP", 2L, new BigDecimal("50.00")});
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        when(invoices.lastNumber("2026-11")).thenReturn(4);
        CommInvoice inv = billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        assertThat(inv.getNumber()).isEqualTo("MSG-202611-0005");
        assertThat(inv.getEmailCount()).isEqualTo(12);
        assertThat(inv.getSmsCount()).isEqualTo(3);
        assertThat(inv.getWhatsappCount()).isEqualTo(2);
        assertThat(inv.getTotalAmount()).isEqualByComparingTo("140");
        assertThat(inv.getStatus()).isEqualTo(CommInvoice.Status.DUE);
        assertThat(inv.getDueOn()).isEqualTo(java.time.LocalDate.now(CommBilling.LAGOS).plusDays(10));
        assertThat(inv.getHotelName()).isEqualTo("Simpas Ikeja");
    }

    @Test
    void numbersAreTakenUnderTheMonthsLockAndTheHotelNameIsReadBeforeIt() {
        invoicesSave("inv-1");
        attached("inv-1", 1, new Object[] {"EMAIL", 1L, new BigDecimal("5.00")});
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        when(invoices.lastNumber("2026-11")).thenReturn(0);
        billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenants, invoices);
        order.verify(tenants).hotel("h1");
        order.verify(invoices).lock("comm-invoice-number:2026-11");
        order.verify(invoices).findByHotelIdAndMonth("h1", "2026-11");
        order.verify(invoices).lastNumber("2026-11");
        order.verify(invoices).saveAndFlush(any());
    }

    @Test
    void stragglersOfEarlierMonthsRollIntoTheInvoice() {
        // Everything unbilled before the month's end (no lower bound): a message of October that only went out after
        // October's invoice is picked up by November's.
        invoicesSave("inv-1");
        attached("inv-1", 2, new Object[] {"SMS", 2L, new BigDecimal("20.00")});
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        org.mockito.ArgumentCaptor<Instant> to = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(deliveries).attachToInvoice(eq("inv-1"), eq("h1"), eq(CommBilling.CHARGED), to.capture());
        assertThat(to.getValue()).isEqualTo(java.time.LocalDate.of(2026, 12, 1).atStartOfDay(CommBilling.LAGOS).toInstant());

        when(deliveries.hotelsToBill(eq(CommBilling.CHARGED), any())).thenReturn(List.of());
        billing.issueAll(YearMonth.of(2026, 11));
        verify(deliveries).hotelsToBill(CommBilling.CHARGED, java.time.LocalDate.of(2026, 12, 1).atStartOfDay(CommBilling.LAGOS).toInstant());
    }

    @Test
    void nothingIsIssuedForAQuietMonthOrTwiceForOneMonth() {
        invoicesSave("inv-q");
        attached("inv-q", 0);
        assertThat(billing.issue("h1", "b1", YearMonth.of(2026, 10))).isEmpty();
        verify(invoices, never()).save(any());
        when(invoices.findByHotelIdAndMonth("h1", "2026-10")).thenReturn(Optional.of(new CommInvoice()));
        assertThat(billing.issue("h1", "b1", YearMonth.of(2026, 10))).isEmpty();
        verify(invoices, org.mockito.Mockito.times(1)).saveAndFlush(any());
        verify(mail, never()).accept(any());
    }

    @Test
    void aNotifyFailureNeverUndoesTheInvoice() {
        invoicesSave("inv-1");
        attached("inv-1", 1, new Object[] {"EMAIL", 1L, new BigDecimal("5.00")});
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        when(mail.accept(any())).thenThrow(new IllegalStateException("broker down"));
        assertThat(billing.issue("h1", "b1", YearMonth.of(2026, 11))).isPresent();
    }

    private CommInvoice due(String id, String hotel) {
        CommInvoice inv = CommInvoice.builder().number("MSG-202610-000" + id.charAt(id.length() - 1)).month("2026-10").status(CommInvoice.Status.DUE)
                .totalAmount(new BigDecimal("15.00")).dueOn(java.time.LocalDate.now(CommBilling.LAGOS).minusDays(1)).build();
        inv.setId(id);
        inv.setHotelId(hotel);
        inv.setBusinessId("b1");
        return inv;
    }

    @Test
    void anInvoicePaidMeanwhileIsNotMadeOverdueAndOneFailureDoesNotStopTheRest() {
        CommInvoice paidMeanwhile = due("inv-1", "h1");
        CommInvoice broken = due("inv-2", "h2");
        CommInvoice late = due("inv-3", "h3");
        when(invoices.findByStatusAndDueOnBefore(eq(CommInvoice.Status.DUE), any())).thenReturn(List.of(paidMeanwhile, broken, late));
        when(invoices.moveStatus(eq("inv-1"), eq(CommInvoice.Status.DUE), eq(CommInvoice.Status.OVERDUE), any())).thenReturn(0);
        when(invoices.moveStatus(eq("inv-2"), eq(CommInvoice.Status.DUE), eq(CommInvoice.Status.OVERDUE), any())).thenThrow(new IllegalStateException("db"));
        when(invoices.moveStatus(eq("inv-3"), eq(CommInvoice.Status.DUE), eq(CommInvoice.Status.OVERDUE), any())).thenReturn(1);
        when(plans.findByHotelId("h3")).thenReturn(Optional.of(CommPlan.builder().businessId("b1").hotelId("h3").enabled(true).build()));

        assertThat(billing.suspendOverdue()).isEqualTo(1);

        verify(plans, never()).findByHotelId("h1");
        verify(invoices, never()).save(any()); // never the stale copy
        org.mockito.ArgumentCaptor<CommPlan> saved = org.mockito.ArgumentCaptor.forClass(CommPlan.class);
        verify(plans).save(saved.capture());
        assertThat(saved.getValue().getHotelId()).isEqualTo("h3");
        assertThat(saved.getValue().getSuspendedAt()).isNotNull();
        verify(notices, org.mockito.Mockito.times(1)).notifyRoles(anyString(), eq("COMM_SUSPENDED"), anyString(), anyString(), anyString(), eq("OWNER"), eq("MANAGER"));
    }

    @Test
    void settlingLocksTheInvoiceAndNotifiesOnlyTheFirstTime() {
        CommInvoice inv = due("inv-1", "h1");
        when(invoices.lockById("inv-1")).thenReturn(Optional.of(inv));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
        billing.settle("inv-1", "PAYSTACK:card", "MSG-202610-0001-AAAAAAAA", "Godwin", null);
        assertThat(inv.getStatus()).isEqualTo(CommInvoice.Status.PAID);
        billing.settle("inv-1", "PAYSTACK:card", "MSG-202610-0001-AAAAAAAA", "Paystack", null); // the webhook, a moment later
        verify(invoices, org.mockito.Mockito.times(1)).save(any());
        verify(invoices, never()).findById(anyString());
        verify(notices, org.mockito.Mockito.times(1)).notifyRoles(anyString(), eq("COMM_INVOICE_PAID"), anyString(), anyString(), anyString(), eq("OWNER"), eq("MANAGER"));
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
        invoicesSave("a8ff37d7-617d-4f3d-9831-9cffa070bf4f");
        attached("a8ff37d7-617d-4f3d-9831-9cffa070bf4f", 3, new Object[] {"EMAIL", 3L, new BigDecimal("15.00")});
        when(invoices.findByHotelIdAndMonth("h1", "2026-11")).thenReturn(Optional.empty());
        billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        org.mockito.ArgumentCaptor<com.hospio.common.kafka.events.DeliveryRequestedEvent> sent = org.mockito.ArgumentCaptor.forClass(com.hospio.common.kafka.events.DeliveryRequestedEvent.class);
        verify(mail, org.mockito.Mockito.times(2)).accept(sent.capture());
        assertThat(sent.getAllValues()).extracting(com.hospio.common.kafka.events.DeliveryRequestedEvent::getTo).containsExactly("godwin@royal.test", "tunde@royal.test");
        assertThat(sent.getAllValues()).allSatisfy(e -> assertThat(e.getKind()).isEqualTo("INVOICE"));
        org.mockito.ArgumentCaptor<String> eventId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notices).notifyRoles(eventId.capture(), eq("COMM_INVOICE"), anyString(), anyString(), anyString(), eq("OWNER"), eq("MANAGER"));
        assertThat(eventId.getValue()).hasSize(36);
    }

    @Test
    void aPaymentFromAnOlderCheckoutStillPaysItsInvoice() {
        CommInvoice inv = CommInvoice.builder().number("MSG-202611-0001").status(CommInvoice.Status.OVERDUE).totalAmount(new BigDecimal("15.00"))
                .paymentReference("MSG-202611-0001-BBBBBBBB").build();
        inv.setId("inv-1");
        inv.setHotelId("h1");
        when(invoices.findByPaymentReference("MSG-202611-0001-AAAAAAAA")).thenReturn(Optional.empty());
        when(invoices.findByNumber("MSG-202611-0001")).thenReturn(Optional.of(inv));
        when(paystack.verify("MSG-202611-0001-AAAAAAAA")).thenReturn(new PaystackClient.Verified(false, BigDecimal.ZERO, null, "Abandoned"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billing.confirm("MSG-202611-0001-AAAAAAAA", "Godwin"))
                .hasMessageContaining("hasn't confirmed");
        assertThat(CommBilling.invoiceNumberOf("MSG-202611-0001-AAAAAAAA")).isEqualTo("MSG-202611-0001");
        assertThat(CommBilling.invoiceNumberOf("T123-abc")).isEmpty();
        assertThat(CommBilling.invoiceNumberOf(null)).isEmpty();
        verify(invoices, never()).save(any());
    }

    @Test
    void severalHotelsPlansAreReadInOneGo() {
        when(plans.findByHotelIdIn(List.of("h1", "h2"))).thenReturn(List.of(CommPlan.builder().businessId("b1").hotelId("h2").enabled(true).build()));
        when(deliveries.unbilledUsageOf(eq(List.of("h1", "h2")), eq(CommBilling.CHARGED), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {"h2", "SMS", 4L, new BigDecimal("40.00")}));
        CommInvoice open = due("inv-9", "h1");
        when(invoices.findByHotelIdInAndStatusIn(eq(List.of("h1", "h2")), any())).thenReturn(List.of(open));
        List<CommBilling.PlanView> views = billing.plansOf(null, List.of("h1", "h2"));
        assertThat(views).extracting(CommBilling.PlanView::hotelId).containsExactly("h1", "h2");
        assertThat(views.get(0).enabled()).isFalse();
        assertThat(views.get(0).outstanding()).isEqualByComparingTo("15");
        assertThat(views.get(1).enabled()).isTrue();
        assertThat(views.get(1).thisMonth().smsCount()).isEqualTo(4);
        verify(plans, never()).findByHotelId(anyString());
        verify(deliveries, never()).unbilledUsage(anyString(), any(), any(), any());
    }

    private static com.hospio.messaging.client.IdentityClient.Contact contact(String email) {
        return new com.hospio.messaging.client.IdentityClient.Contact("u", "Someone", email, null, "b1", "ACTIVE", List.of());
    }
}
