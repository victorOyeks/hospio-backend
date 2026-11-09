package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.integrations.LoggingPosProvider;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.client.HotelConfigClient;
import com.hospio.payment.client.ReservationClient;
import com.hospio.payment.dto.PaymentRequestDto;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.ReceiptRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Cash is approved on the spot; a role without a POS can't take cards; an offline POS means cash only. */
class PaymentServiceTest {

    private final PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    private final ReceiptRepository receipts = mock(ReceiptRepository.class);
    private final TerminalService terminals = mock(TerminalService.class);
    private final HotelConfigClient hotelConfig = mock(HotelConfigClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ReservationClient reservations = mock(ReservationClient.class);
    private final PaymentService service = new PaymentService(payments, receipts, terminals, new LoggingPosProvider(), hotelConfig, events, reservations);

    @BeforeEach
    void setUp() {
        when(reservations.booking(anyString())).thenReturn(Optional.of(new ReservationClient.BookingState("BOOKED", "h1")));
        when(payments.save(any())).thenAnswer(inv -> {
            var p = (com.hospio.payment.entity.PaymentRequest) inv.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-00000001");
            }
            p.setHotelId("h1");
            return p;
        });
        when(receipts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receipts.findByPaymentId(anyString())).thenReturn(Optional.empty());
        when(hotelConfig.paymentSettings("h1")).thenReturn(HotelConfigClient.PaymentSettings.defaults());
        HospioUserDetails user = new HospioUserDetails("u1", "bar@x.ng", "Segun", "b1", List.of("h1"), Map.of(Role.BAR, AccessLevel.EDIT), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void cashIsApprovedOnTheSpot() {
        PaymentResponse p = service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("13650"), null, null, "Walk-in", null, null, "Drinks", null));

        assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(events).publish(eq(KafkaTopics.PAYMENT_APPROVED), any());
    }

    @Test
    void aCancelledBookingCannotTakeAPayment() {
        when(reservations.booking("b1")).thenReturn(Optional.of(new ReservationClient.BookingState("CANCELLED", "h1")));

        assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("5000"), null, "b1", "Guest", null, null, "Deposit", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cancelled");
    }

    @Test
    void anotherHotelsBookingIsNotFound() {
        when(reservations.booking("b2")).thenReturn(Optional.of(new ReservationClient.BookingState("BOOKED", "other")));

        assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("5000"), null, "b2", "Guest", null, null, "Deposit", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not found");
    }

    @Test
    void roleWithoutAPosCannotTakeCards() {
        when(terminals.forRole("h1", Role.BAR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CARD, new BigDecimal("5000"), null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("No POS is assigned to bar");
    }

    @Test
    void offlinePosMeansCashOnly() {
        PosTerminal t = PosTerminal.builder().name("Bar POS").provider("logging").terminalRef("T1").status(PosTerminal.Status.OFFLINE).build();
        when(terminals.forRole("h1", Role.BAR)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CARD, new BigDecimal("5000"), null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("offline");
    }

    @Test
    void aConfirmedTransferIsApprovedWithoutAPos() {
        when(terminals.forRole("h1", Role.BAR)).thenReturn(Optional.empty());

        PaymentResponse p = service.request(new PaymentRequestDto(PaymentMethod.TRANSFER, new BigDecimal("5000"), null, "bk1", "Ada", null, null, "Deposit", null));

        assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(events).publish(eq(KafkaTopics.PAYMENT_APPROVED), any());
    }

    @Test
    void cardGoesToThePosAndWaits() {
        PosTerminal t = PosTerminal.builder().name("Bar POS").provider("logging").terminalRef("T1").build();
        t.setId("t1");
        when(terminals.forRole("h1", Role.BAR)).thenReturn(Optional.of(t));

        PaymentResponse p = service.request(new PaymentRequestDto(PaymentMethod.CARD, new BigDecimal("5000"), "f1", null, "Ada", null, null, "Room 12", null));

        assertThat(p.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(p.providerRef()).startsWith("LOG-");
        verify(events).publish(eq(KafkaTopics.PAYMENT_REQUESTED), any());
    }
}
