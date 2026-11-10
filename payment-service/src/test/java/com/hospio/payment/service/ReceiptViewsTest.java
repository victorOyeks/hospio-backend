package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Receipt;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.ReceiptRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Receipts to print and email: one per approved payment, the caller's hotel only, a sale's in order, a valid address. */
class ReceiptViewsTest {

    private final PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    private final ReceiptRepository receipts = mock(ReceiptRepository.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final ReceiptService service = new ReceiptService(payments, receipts, deliveries);

    private PaymentRequest pay(String id, String hotel, PaymentStatus status, String amount) {
        PaymentRequest p = PaymentRequest.builder().method(PaymentMethod.CASH).amount(new BigDecimal(amount)).status(status).role(Role.BAR)
                .requestedByName("Segun Ajayi").orderId("o1").guestName("QA ML Guest").build();
        p.setId(id);
        p.setHotelId(hotel);
        p.setCreatedAt(Instant.now());
        when(payments.findById(id)).thenReturn(Optional.of(p));
        Receipt r = Receipt.builder().paymentId(id).number("RCP-2026-0000" + id.substring(1)).build();
        r.setCreatedAt(Instant.now());
        when(receipts.findByPaymentId(id)).thenReturn(Optional.of(r));
        return p;
    }

    @BeforeEach
    void setUp() {
        HospioUserDetails u = new HospioUserDetails("u1", "segun@x.ng", "Segun", "b1", List.of("h1"), Map.of(Role.BAR, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        TenantContext.set(Tenant.fromUser(u, "h1"));
        when(deliveries.send(any(Delivery.class))).thenAnswer(i -> List.of((Delivery) i.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void oneApprovedPaymentHasItsReceipt() {
        pay("p1", "h1", PaymentStatus.APPROVED, "4500");
        ReceiptViews.ReceiptView v = service.view("p1");
        assertThat(v.number()).isEqualTo("RCP-2026-00001");
        assertThat(v.amount()).isEqualByComparingTo("4500");
        assertThat(v.takenBy()).isEqualTo("Segun Ajayi");
        assertThat(v.method()).isEqualTo("CASH");
    }

    @Test
    void pendingOrOtherHotelHasNone() {
        pay("p2", "h1", PaymentStatus.PENDING, "100");
        assertThatThrownBy(() -> service.view("p2")).isInstanceOf(BadRequestException.class).hasMessage("Only an approved payment has a receipt");
        pay("p3", "h2", PaymentStatus.APPROVED, "100");
        assertThatThrownBy(() -> service.view("p3")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSaleListsItsReceiptsOldestFirstAndTotalsMatch() {
        PaymentRequest a = pay("p4", "h1", PaymentStatus.APPROVED, "3000");
        PaymentRequest b = pay("p5", "h1", PaymentStatus.APPROVED, "1500");
        PaymentRequest other = pay("p6", "h2", PaymentStatus.APPROVED, "999");
        when(payments.findByOrderIdAndStatusOrderByCreatedAtAsc("o1", PaymentStatus.APPROVED)).thenReturn(List.of(a, b, other));
        List<ReceiptViews.ReceiptView> list = service.forRef("o1", null);
        assertThat(list).extracting(ReceiptViews.ReceiptView::paymentId).containsExactly("p4", "p5");
        assertThat(list.stream().map(ReceiptViews.ReceiptView::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("4500");
        assertThatThrownBy(() -> service.forRef(null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.forRef("o1", "f1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emailNeedsOneRefAndAnAddress() {
        pay("p7", "h1", PaymentStatus.APPROVED, "2000");
        assertThatThrownBy(() -> service.email(new ReceiptViews.EmailRequest(null, null, null, "a@b.ng", null)))
                .hasMessage("Say which receipt: a payment, a sale or a folio");
        assertThatThrownBy(() -> service.email(new ReceiptViews.EmailRequest("p7", null, null, "not-an-email", null)))
                .hasMessage("not-an-email is not an email address");
        assertThatThrownBy(() -> service.email(new ReceiptViews.EmailRequest("p7", null, null, " ", null)))
                .hasMessage("Give the email address to send the receipt to");
        ReceiptViews.Emailed e = service.email(new ReceiptViews.EmailRequest("p7", null, null, "qa.guest@hospio-qa.test", "Receipt RCP-2026-00007\nTotal ₦2,000.00"));
        assertThat(e.to()).isEqualTo("qa.guest@hospio-qa.test");
        assertThat(e.deliveries()).hasSize(1);
        assertThat(e.deliveries().get(0).subject()).isEqualTo("Your receipt RCP-2026-00007");
        assertThat(e.deliveries().get(0).text()).contains("₦2,000.00");
    }

    @Test
    void textFallsBackToOneLinePerPaymentAndIsCapped() {
        String longText = "x".repeat(ReceiptViews.MAX_TEXT + 50);
        assertThat(ReceiptViews.text(longText, List.of(), null)).hasSize(ReceiptViews.MAX_TEXT);
    }
}
