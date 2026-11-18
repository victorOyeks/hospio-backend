package com.hospio.housekeeping.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hospio.common.billing.BillingHold;
import com.hospio.common.exception.BillingHoldException;
import com.hospio.housekeeping.dto.LaundryDtos;
import com.hospio.housekeeping.service.HousekeepingLists;
import com.hospio.housekeeping.service.LaundryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * While a business is paused for an unpaid Hospio360 invoice (docs/plan-monthly-invoices.md) a new guest-laundry order is refused;
 * orders already taken still move, are charged to the room, paid and cancelled.
 */
class LaundryBillingHoldTest {

    private final LaundryService service = mock(LaundryService.class);
    private final BillingHold hold = mock(BillingHold.class);
    private final LaundryController controller = controller(hold);

    private LaundryController controller(BillingHold billingHold) {
        LaundryController c = new LaundryController(service, mock(HousekeepingLists.class));
        ReflectionTestUtils.setField(c, "billingHold", billingHold);
        return c;
    }

    private static LaundryDtos.OrderRequest request() {
        return new LaundryDtos.OrderRequest("room-1", List.of(), false, null, null, false);
    }

    @Test
    void aNewOrderIsRefusedWhilePaused() {
        doThrow(new BillingHoldException("New laundry orders are paused: the Hospio360 invoice HIN-202611-0001 is unpaid."))
                .when(hold).require("New laundry orders");

        assertThatThrownBy(() -> controller.create(request())).isInstanceOf(BillingHoldException.class)
                .hasMessageStartingWith("New laundry orders are paused");
        verify(service, never()).create(any());
    }

    @Test
    void aNewOrderIsTakenWhenNotPaused() {
        LaundryDtos.OrderRequest r = request();
        controller.create(r);

        verify(hold).require("New laundry orders");
        verify(service).create(r);
    }

    @Test
    void ordersAlreadyTakenStillMoveAndSettleWhilePaused() {
        doThrow(new BillingHoldException("paused")).when(hold).require(any());

        controller.status("o1", new LaundryDtos.StatusRequest("READY"));
        controller.chargeToRoom("o1");
        controller.paid("o1", new LaundryDtos.PaidRequest("pay-1"));
        controller.cancel("o1", new LaundryDtos.ReasonRequest("guest left"));

        verifyNoInteractions(hold);
        verify(service).move(any(), any());
        verify(service).chargeToRoom("o1");
        verify(service).paid(any(), any());
        verify(service).cancel(any(), any());
    }

    @Test
    void withoutTheCheckWiredOrdersAreTaken() {
        LaundryDtos.OrderRequest r = request();
        controller(null).create(r);

        verify(service).create(r);
    }
}
