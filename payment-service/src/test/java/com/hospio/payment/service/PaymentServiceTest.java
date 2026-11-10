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
    private final com.hospio.payment.client.FnbClient fnb = mock(com.hospio.payment.client.FnbClient.class);
    private final PaymentService service = new PaymentService(payments, receipts, terminals, new LoggingPosProvider(), hotelConfig, events, reservations, fnb);

    @BeforeEach
    void setUp() {
        when(reservations.booking(anyString())).thenReturn(Optional.of(new ReservationClient.BookingState("BOOKED", "h1")));
        when(fnb.order(anyString())).thenReturn(Optional.of(new com.hospio.payment.client.FnbClient.OrderDue("h1", "B-0007", "SERVED", false, new BigDecimal("3000"), "BAR")));
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

    @Test
    void aDeclinedCardIsRetriedOnTheSameDesksPos() {
        var old = com.hospio.payment.entity.PaymentRequest.builder().method(PaymentMethod.CARD).amount(new BigDecimal("5000")).role(Role.BAR)
                .bookingId("bk1").guestName("Ada").description("Room 12").requestedBy("u1").status(PaymentStatus.DECLINED).build();
        old.setId("old1");
        old.setHotelId("h1");
        when(payments.findById("old1")).thenReturn(Optional.of(old));
        PosTerminal t = PosTerminal.builder().name("Bar POS").provider("logging").terminalRef("T1").build();
        t.setId("t1");
        when(terminals.forRole("h1", Role.BAR)).thenReturn(Optional.of(t));

        PaymentResponse p = service.retry("old1");

        assertThat(p.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(p.amount()).isEqualByComparingTo("5000");
        assertThat(p.bookingId()).isEqualTo("bk1");
        assertThat(old.getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void anApprovedPaymentIsNotRetried() {
        var old = com.hospio.payment.entity.PaymentRequest.builder().method(PaymentMethod.CARD).amount(new BigDecimal("5000")).role(Role.BAR)
                .requestedBy("u1").status(PaymentStatus.APPROVED).build();
        old.setId("old2");
        old.setHotelId("h1");
        when(payments.findById("old2")).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.retry("old2")).isInstanceOf(BadRequestException.class).hasMessageContaining("declined");
    }

    private static HospioUserDetails holding(Role... roles) {
        Map<Role, AccessLevel> m = new java.util.LinkedHashMap<>();
        for (Role r : roles) {
            m.put(r, AccessLevel.EDIT);
        }
        return new HospioUserDetails("u2", "multi@x.ng", "Multi", "b1", List.of("h1"), m, JwtUtil.SCOPE_HOTEL);
    }

    @Test
    void withoutARoleTheDeskIsChosenInAFixedOrderWhateverOrderTheCallerHoldsThem() {
        assertThat(PaymentService.payingRole(holding(Role.KITCHEN, Role.BAR, Role.FRONT_OFFICE), null)).isEqualTo(Role.FRONT_OFFICE);
        assertThat(PaymentService.payingRole(holding(Role.KITCHEN, Role.BAR), null)).isEqualTo(Role.BAR);
        assertThat(PaymentService.payingRole(holding(Role.MANAGER, Role.KITCHEN), null)).isEqualTo(Role.KITCHEN);
        assertThat(PaymentService.payingRole(holding(Role.OWNER, Role.MANAGER), null)).isEqualTo(Role.MANAGER);
    }

    @Test
    void aRequestedRoleMustBeOneOfTheCallersOwn() {
        assertThat(PaymentService.payingRole(holding(Role.BAR, Role.KITCHEN), Role.KITCHEN)).isEqualTo(Role.KITCHEN);
        assertThatThrownBy(() -> PaymentService.payingRole(holding(Role.BAR), Role.KITCHEN)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PaymentService.payingRole(holding(Role.HOUSEKEEPING), null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aPaymentForAnOrderCarriesTheOrderIdOnTheRequestAndTheEvent() {
        PaymentResponse p = service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("3000"), null, null, null, null, null, "Bar order B-0007", null,
                "order-7", Role.BAR));

        assertThat(p.orderId()).isEqualTo("order-7");
        assertThat(p.role()).isEqualTo(Role.BAR);
        var captor = org.mockito.ArgumentCaptor.forClass(com.hospio.common.kafka.events.PaymentApprovedEvent.class);
        verify(events).publish(eq(KafkaTopics.PAYMENT_APPROVED), captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo("order-7");
        // Income desk from the order's outlet travels with the event; role stays the payer's desk.
        assertThat(captor.getValue().getDesk()).isEqualTo("BAR");
        assertThat(captor.getValue().getRole()).isEqualTo("BAR");
    }

    @Test
    void aRoleTheCallerDoesNotHoldIsRefused() {
        assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("3000"), null, null, null, null, null, null, null, null, Role.KITCHEN)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void noPaymentForACancelledSettledOrUnknownOrderOrMoreThanIsDue() {
        when(fnb.order("cancelled")).thenReturn(Optional.of(new com.hospio.payment.client.FnbClient.OrderDue("h1", "K-0002", "CANCELLED", false, new BigDecimal("3000"), "BAR")));
        when(fnb.order("settled")).thenReturn(Optional.of(new com.hospio.payment.client.FnbClient.OrderDue("h1", "K-0003", "SERVED", true, BigDecimal.ZERO, "KITCHEN")));
        when(fnb.order("missing")).thenReturn(Optional.empty());
        for (String id : List.of("cancelled", "settled", "missing")) {
            assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("1000"), null, null, null, null, null, null, null, id, Role.BAR)))
                    .as(id).isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> service.request(new PaymentRequestDto(PaymentMethod.CASH, new BigDecimal("3500"), null, null, null, null, null, null, null, "order-7", Role.BAR)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("₦3,000");
    }
}
