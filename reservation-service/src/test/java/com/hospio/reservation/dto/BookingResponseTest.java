package com.hospio.reservation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.enums.PaymentPlan;
import com.hospio.reservation.entity.Booking;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Before check-in the booking is the bill; once the folio exists, its total, payments and balance are. */
class BookingResponseTest {

    private static Booking stay() {
        return Booking.builder().reference("HBCDDEJ6").guestId("g1").guestName("QA LM Guest 7171").roomTypeId("t1")
                .checkInDate(LocalDate.of(2026, 11, 10)).checkOutDate(LocalDate.of(2026, 11, 12)).status(BookingStatus.CHECKED_IN)
                .nightlyRate(new BigDecimal("35000")).totalAmount(new BigDecimal("70000")).paymentPlan(PaymentPlan.PAY_AT_CHECKOUT).build();
    }

    @Test
    void beforeTheFolioTheBookingIsTheBill() {
        Booking b = stay();
        b.setAmountPaid(new BigDecimal("20000"));
        BookingResponse r = BookingResponse.of(b);
        assertThat(r.billTotal()).isEqualByComparingTo("70000");
        assertThat(r.billPaid()).isEqualByComparingTo("20000");
        assertThat(r.balance()).isEqualByComparingTo("50000");
    }

    @Test
    void withAFolioItsNumbersAreTheBill() {
        Booking b = stay();
        b.setFolioTotal(new BigDecimal("87650"));
        b.setFolioPaid(new BigDecimal("87650"));
        b.setFolioBalance(BigDecimal.ZERO);
        BookingResponse r = BookingResponse.of(b).withGuest("+2348066077171", null);
        assertThat(r.totalAmount()).isEqualByComparingTo("70000");
        assertThat(r.billTotal()).isEqualByComparingTo("87650");
        assertThat(r.billPaid()).isEqualByComparingTo("87650");
        assertThat(r.balance()).isEqualByComparingTo("0");
    }
}
