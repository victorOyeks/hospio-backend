package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.platform.entity.FeeStatement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** The fee per model, when a statement falls due, and when it becomes overdue. */
class FeeServiceTest {

    @Test
    void percentOfPaymentsTakesThePercentOfTheBase() {
        assertThat(FeeService.computeFee(FeeService.PERCENT_OF_PAYMENTS, new BigDecimal("2.5"), new BigDecimal("1000000"), 0))
                .isEqualByComparingTo("25000.00");
    }

    @Test
    void percentOfBookingsTakesThePercentOfTheBookingValue() {
        assertThat(FeeService.computeFee(FeeService.PERCENT_OF_BOOKINGS, new BigDecimal("2"), new BigDecimal("450000.50"), 120))
                .isEqualByComparingTo("9000.01");
    }

    @Test
    void perRoomNightMultipliesTheNightsSold() {
        assertThat(FeeService.computeFee(FeeService.PER_ROOM_NIGHT, new BigDecimal("1500"), null, 310)).isEqualByComparingTo("465000.00");
        assertThat(FeeService.computeFee("per_room_night", new BigDecimal("1500"), null, 0)).isEqualByComparingTo("0.00");
    }

    @Test
    void unknownModelIsRefused() {
        assertThatThrownBy(() -> FeeService.computeFee("FLAT", BigDecimal.ONE, BigDecimal.TEN, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void dueOnIsTheDueDayOfTheNextMonthClampedToItsLength() {
        assertThat(FeeService.dueOn(YearMonth.of(2026, 11), 5)).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(FeeService.dueOn(YearMonth.of(2026, 1), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void statementIsDueUntilGraceEndsThenOverdue() {
        FeeStatement s = FeeStatement.builder().feeAmount(new BigDecimal("25000")).paidAmount(BigDecimal.ZERO).dueOn(LocalDate.of(2026, 12, 5)).build();
        assertThat(FeeService.status(s, LocalDate.of(2026, 12, 15), 10)).isEqualTo("DUE");
        assertThat(FeeService.status(s, LocalDate.of(2026, 12, 16), 10)).isEqualTo("OVERDUE");
    }

    @Test
    void partPaymentAndFullPaymentChangeTheStatus() {
        FeeStatement s = FeeStatement.builder().feeAmount(new BigDecimal("25000")).paidAmount(new BigDecimal("10000")).dueOn(LocalDate.of(2026, 12, 5)).build();
        assertThat(FeeService.status(s, LocalDate.of(2026, 12, 1), 10)).isEqualTo("PART_PAID");
        assertThat(FeeService.status(s, LocalDate.of(2027, 1, 1), 10)).isEqualTo("OVERDUE");
        s.setPaidAmount(new BigDecimal("25000"));
        assertThat(FeeService.status(s, LocalDate.of(2027, 1, 1), 10)).isEqualTo("PAID");
        assertThat(s.outstanding()).isEqualByComparingTo("0");
    }

    @Test
    void zeroFeeStatementIsPaid() {
        FeeStatement s = FeeStatement.builder().feeAmount(BigDecimal.ZERO).dueOn(LocalDate.of(2026, 12, 5)).build();
        assertThat(FeeService.status(s, LocalDate.of(2027, 3, 1), 10)).isEqualTo("PAID");
    }
}
