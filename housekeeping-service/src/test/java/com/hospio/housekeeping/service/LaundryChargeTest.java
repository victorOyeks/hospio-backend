package com.hospio.housekeeping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.housekeeping.client.LaundryClients;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.dto.LaundryDtos;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.repository.LaundryOrderRepository;
import com.hospio.housekeeping.repository.LaundryPriceRepository;
import com.hospio.housekeeping.repository.LinenBatchRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A laundry charge reaches the guest's bill only while they are checked in with an open bill, for exactly the order total. */
class LaundryChargeTest {

    private LaundryOrderRepository orders;
    private ReservationClient reservations;
    private LaundryClients clients;
    private EventPublisher events;
    private LaundryService service;
    private LaundryOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(LaundryOrderRepository.class);
        reservations = mock(ReservationClient.class);
        clients = mock(LaundryClients.class);
        events = mock(EventPublisher.class);
        service = new LaundryService(orders, mock(LaundryPriceRepository.class), mock(LinenBatchRepository.class), reservations, clients, events, new ObjectMapper());
        order = LaundryOrder.builder().number("LDR-00007").roomId("r1").roomNumber("A105").bookingId("bk1").guestName("Ada Obi").items("3 × Shirt")
                .total(new BigDecimal("4500.00")).status(LaundryOrder.Status.RECEIVED).build();
        order.setId("o1");
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void chargesTheOrderTotalToTheOpenBill() {
        when(reservations.activeStay("r1")).thenReturn(Optional.of(new ReservationClient.ActiveStay("bk1", "Ada Obi", "A105")));
        when(clients.hasOpenFolio("bk1")).thenReturn(true);
        LaundryDtos.OrderView v = service.chargeToRoom("o1");
        assertThat(v.settlement()).isEqualTo("ROOM");
        ArgumentCaptor<ChargePostedEvent> e = ArgumentCaptor.forClass(ChargePostedEvent.class);
        verify(events).publish(eq(KafkaTopics.CHARGE_POSTED), e.capture());
        assertThat(e.getValue().getAmount()).isEqualByComparingTo(order.getTotal());
        assertThat(e.getValue().getSourceRef()).isEqualTo("LAUNDRY-o1");
        assertThat(e.getValue().getCategory()).isEqualTo("LAUNDRY");
        assertThatThrownBy(() -> service.chargeToRoom("o1")).hasMessage("LDR-00007 is already charged to the room");
    }

    @Test
    void refusesAGuestWhoIsNotCheckedIn() {
        when(reservations.activeStay("r1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.chargeToRoom("o1")).isInstanceOf(BadRequestException.class)
                .hasMessage("Nobody is checked in to room A105 now · take payment instead");
        verify(events, never()).publish(any(), any());
    }

    @Test
    void refusesWhenAnotherGuestHasTheRoomNow() {
        when(reservations.activeStay("r1")).thenReturn(Optional.of(new ReservationClient.ActiveStay("bk2", "Someone Else", "A105")));
        assertThatThrownBy(() -> service.chargeToRoom("o1")).hasMessage("Ada Obi has checked out of room A105 · take payment instead");
    }

    @Test
    void refusesAStayWithNoOpenBill() {
        when(reservations.activeStay("r1")).thenReturn(Optional.of(new ReservationClient.ActiveStay("bk1", "Ada Obi", "A105")));
        when(clients.hasOpenFolio("bk1")).thenReturn(false);
        assertThatThrownBy(() -> service.chargeToRoom("o1")).hasMessage("Room A105 has no open bill · take payment instead");
        verify(events, never()).publish(any(), any());
    }

    @Test
    void aPaymentMustBeApprovedAndCoverTheOrder() {
        when(clients.payment("p1")).thenReturn(new LaundryClients.Payment("p1", "PENDING", new BigDecimal("4500"), "CARD"));
        assertThatThrownBy(() -> service.paid("o1", new LaundryDtos.PaidRequest("p1"))).hasMessage("That payment isn't approved (pending)");
        when(clients.payment("p2")).thenReturn(new LaundryClients.Payment("p2", "APPROVED", new BigDecimal("4000"), "CASH"));
        assertThatThrownBy(() -> service.paid("o1", new LaundryDtos.PaidRequest("p2"))).hasMessage("That payment (4000.00) is less than LDR-00007 (4500.00)");
        when(orders.existsByPaymentId("p3")).thenReturn(true);
        assertThatThrownBy(() -> service.paid("o1", new LaundryDtos.PaidRequest("p3"))).hasMessage("That payment is already used for another laundry order");
        when(clients.payment("p4")).thenReturn(new LaundryClients.Payment("p4", "APPROVED", new BigDecimal("4500"), "CASH"));
        assertThat(service.paid("o1", new LaundryDtos.PaidRequest("p4")).settlement()).isEqualTo("PAID");
        assertThatThrownBy(() -> service.cancel("o1", new LaundryDtos.ReasonRequest("guest changed mind"))).hasMessage("LDR-00007 is already paid · refund the payment instead");
    }
}
