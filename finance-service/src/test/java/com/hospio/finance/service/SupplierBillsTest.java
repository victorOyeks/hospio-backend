package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.events.DeliveryReceivedEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.entity.Bill;
import com.hospio.finance.repository.BillRepository;
import com.hospio.finance.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Supplier bills from deliveries: one bill per delivery for the value received, due by the supplier's terms, under Stock purchases;
 * a replay or a delivery worth nothing makes none; who may see a delivery's bill, Owed to suppliers and the double-payment hint.
 */
class SupplierBillsTest {

    private final BillRepository bills = mock(BillRepository.class);
    private final ExpenseCategories categories = mock(ExpenseCategories.class);
    private final SupplierBills supplierBills = new SupplierBills(bills, categories);
    private final BillService service = new BillService(bills, mock(ExpenseRepository.class), categories, mock(MoneyService.class),
            mock(com.hospio.common.kafka.EventPublisher.class), null);
    private final AtomicReference<Bill> saved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(Tenant.systemFor("b1", "h1", "u-jide", "Jide Store"));
        when(categories.ensure("h1", ExpenseCategories.STOCK_PURCHASES)).thenReturn("STOCK_PURCHASES");
        when(categories.labels(any())).thenReturn(Map.of("STOCK_PURCHASES", "Stock purchases"));
        when(bills.save(any())).thenAnswer(i -> {
            Bill b = i.getArgument(0);
            b.setId("bill1");
            b.setHotelId("h1");
            saved.set(b);
            return b;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static DeliveryReceivedEvent delivery(String total, Integer termsDays) {
        return DeliveryReceivedEvent.builder().deliveryId("3f9a2c1b-0000-4000-8000-000000000001").purchaseOrderId("po1")
                .purchaseOrderNumber("PO-2026-00007").supplierId("sup1").supplierName("QA Supplies Ltd").total(new BigDecimal(total))
                .invoiceNumber("INV-77").receivedOn("2026-11-21").paymentTerms(termsDays == null ? null : termsDays + " days").termsDays(termsDays)
                .hotelId("h1").businessId("b1").actorName("Jide Store").build();
    }

    private void signIn(Map<Role, AccessLevel> roles) {
        HospioUserDetails u = new HospioUserDetails("u1", "u@x.ng", "Femi Ojo", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @Test
    void aReceivedDeliveryBecomesOneUnpaidBillToTheSupplier() {
        Optional<Bill> made = supplierBills.fromDelivery(delivery("12520", 14));

        assertThat(made).isPresent();
        Bill b = saved.get();
        assertThat(b.getPayee()).isEqualTo("QA Supplies Ltd");
        assertThat(b.getSupplierId()).isEqualTo("sup1");
        assertThat(b.getAmount()).isEqualByComparingTo("12520"); // the value received: damaged units were never in the event's total
        assertThat(b.getCategory()).isEqualTo("STOCK_PURCHASES");
        assertThat(b.getStatus()).isEqualTo(Bill.Status.UNPAID);
        assertThat(b.getRepeats()).isEqualTo(Bill.Repeats.NONE);
        assertThat(b.getDeliveryId()).isEqualTo("3f9a2c1b-0000-4000-8000-000000000001");
        assertThat(b.getPurchaseOrderId()).isEqualTo("po1");
        assertThat(b.getInvoiceNumber()).isEqualTo("INV-77");
        assertThat(b.getNote()).isEqualTo("Delivery 3F9A2C1B · PO-2026-00007 · invoice INV-77 · 14 days");
        assertThat(b.getCreatedByName()).isEqualTo("Jide Store");
        assertThat(b.getPaidAt()).isNull();
    }

    @Test
    void dueOnTheReceivedDatePlusTheSuppliersTerms() {
        supplierBills.fromDelivery(delivery("5000", 14));
        assertThat(saved.get().getDueDate()).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    void withoutTermsItIsDueTheDayItArrived() {
        supplierBills.fromDelivery(delivery("5000", null));
        assertThat(saved.get().getDueDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(saved.get().getNote()).isEqualTo("Delivery 3F9A2C1B · PO-2026-00007 · invoice INV-77");
    }

    @Test
    void aReplayedDeliveryMakesNoSecondBill() {
        when(bills.existsByDeliveryId("3f9a2c1b-0000-4000-8000-000000000001")).thenReturn(false, true);
        assertThat(supplierBills.fromDelivery(delivery("5000", 7))).isPresent();
        assertThat(supplierBills.fromDelivery(delivery("5000", 7))).isEmpty();
        verify(bills, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void aDeliveryWorthNothingMakesNoBill() {
        assertThat(supplierBills.fromDelivery(delivery("0", 14))).isEmpty();
        DeliveryReceivedEvent noTotal = delivery("1", 14);
        noTotal.setTotal(null);
        assertThat(supplierBills.fromDelivery(noTotal)).isEmpty();
        verify(bills, never()).save(any());
    }

    @Test
    void deliveriesFromBeforeSupplierBillsStayUnbilled() {
        DeliveryReceivedEvent old = delivery("5000", null);
        old.setReceivedOn(null);
        assertThat(supplierBills.fromDelivery(old)).isEmpty();
        verify(bills, never()).save(any());
    }

    @Test
    void aWalkInDeliveryWithoutOrderOrInvoiceStillGetsItsBill() {
        DeliveryReceivedEvent e = DeliveryReceivedEvent.builder().deliveryId("abcdef12-3456").supplierName("Mama Put Foods").total(new BigDecimal("2600"))
                .receivedOn("2026-11-21").termsDays(0).hotelId("h1").build();
        supplierBills.fromDelivery(e);
        assertThat(saved.get().getNote()).isEqualTo("Delivery ABCDEF12");
        assertThat(saved.get().getPurchaseOrderId()).isNull();
        assertThat(saved.get().getDueDate()).isEqualTo(LocalDate.of(2026, 11, 21));
    }

    // ---- who sees what -------------------------------------------------------------------------------

    @Test
    void theStoreSeesItsDeliverysBillOthersDont() {
        Bill b = Bill.builder().payee("QA Supplies Ltd").category("STOCK_PURCHASES").amount(new BigDecimal("12520")).dueDate(LocalDate.of(2099, 1, 1))
                .deliveryId("d1").build();
        b.setId("bill1");
        b.setHotelId("h1");
        when(bills.findFirstByDeliveryId("d1")).thenReturn(Optional.of(b));

        signIn(Map.of(Role.INVENTORY, AccessLevel.EDIT));
        assertThat(service.forDelivery("d1")).hasValueSatisfying(v -> {
            assertThat(v.status()).isEqualTo("UNPAID");
            assertThat(v.amount()).isEqualByComparingTo("12520");
        });
        // The store can't read the bills list itself.
        assertThatThrownBy(() -> service.owed(null)).isInstanceOf(ForbiddenException.class);
        signIn(Map.of(Role.FRONT_OFFICE, AccessLevel.FULL));
        assertThatThrownBy(() -> service.forDelivery("d1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.matches(BigDecimal.TEN, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void financeViewReadsOwedButCannotPay() {
        signIn(Map.of(Role.FINANCE, AccessLevel.VIEW));
        when(bills.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<Bill>>any())).thenReturn(List.of());
        assertThat(service.owed("OVERDUE").suppliers()).isEmpty();
        assertThatThrownBy(() -> service.pay("bill1", null)).isInstanceOf(ForbiddenException.class);
    }

    // ---- Owed to suppliers and the hint -----------------------------------------------------------

    private static Bill bill(String payee, String amount, LocalDate due, Bill.Status status) {
        Bill b = Bill.builder().payee(payee).category("STOCK_PURCHASES").amount(new BigDecimal(amount)).dueDate(due).status(status).build();
        b.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));
        return b;
    }

    @Test
    void owedGroupsPerSupplierWithOverdueAndTheOldestDueDate() {
        LocalDate today = LocalDate.of(2026, 11, 21);
        BillService.Owed o = BillService.group("UNPAID", List.of(
                bill("QA Supplies Ltd", "12520", LocalDate.of(2026, 11, 10), Bill.Status.UNPAID),
                bill("qa supplies ltd", "5000", LocalDate.of(2026, 12, 5), Bill.Status.UNPAID),
                bill("Ikeja Electric", "30000", LocalDate.of(2026, 11, 30), Bill.Status.UNPAID)), today);

        assertThat(o.suppliers()).hasSize(2);
        assertThat(o.suppliers().get(0).payee()).isEqualTo("Ikeja Electric"); // most owed first
        BillService.SupplierOwed qa = o.suppliers().get(1);
        assertThat(qa.bills()).isEqualTo(2);
        assertThat(qa.total()).isEqualByComparingTo("17520");
        assertThat(qa.overdue()).isEqualByComparingTo("12520");
        assertThat(qa.overdueBills()).isEqualTo(1);
        assertThat(qa.oldestDue()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(o.total()).isEqualByComparingTo("47520");
        assertThat(o.overdue()).isEqualByComparingTo("12520");
    }

    @Test
    void theHintFindsAnUnpaidBillOfAboutTheSameAmountNamingTheSupplierFirst() {
        Bill qa = bill("CleanPro Supplies", "12520", LocalDate.of(2026, 11, 30), Bill.Status.UNPAID);
        Bill other = bill("Mama Put Foods", "12400", LocalDate.of(2026, 11, 30), Bill.Status.UNPAID);
        Bill far = bill("Bottled Water Co", "50000", LocalDate.of(2026, 11, 30), Bill.Status.UNPAID);

        assertThat(BillService.match(List.of(other, qa, far), new BigDecimal("12500"), "Paid cleanpro for soap")).containsExactly(qa, other);
        assertThat(BillService.match(List.of(other, qa, far), new BigDecimal("12450"), null)).containsExactly(other, qa);
        // Named but far off in amount: still a hint up to half; beyond, nothing.
        assertThat(BillService.match(List.of(qa), new BigDecimal("8000"), "CleanPro top-up")).containsExactly(qa);
        assertThat(BillService.match(List.of(qa), new BigDecimal("3000"), "CleanPro top-up")).isEmpty();
        assertThat(BillService.match(List.of(far), new BigDecimal("12500"), "water")).isEmpty();
    }

    @Test
    void theHintNeedsAnAmount() {
        signIn(Map.of(Role.FINANCE, AccessLevel.EDIT));
        assertThat(service.matches(null, "CleanPro")).isEmpty();
        verify(bills, never()).findByHotelIdAndStatusAndDeliveryIdIsNotNullAndCreatedAtGreaterThanEqual(anyString(), eq(Bill.Status.UNPAID), any());
    }
}
