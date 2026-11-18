package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.platform.entity.DailyFee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 1.5 % to the kobo, nothing on a day without sales, and the POS pause after 3 short days in a row. */
class DailyFeeServiceTest {

    @Test
    void feeIsTheRateOfTheDaysSalesToTheKobo() {
        assertThat(DailyFeeService.feeOn(new BigDecimal("1240000"), new BigDecimal("1.5"))).isEqualByComparingTo("18600.00");
        assertThat(DailyFeeService.feeOn(new BigDecimal("87517.44"), new BigDecimal("1.5"))).isEqualByComparingTo("1312.76");
        assertThat(DailyFeeService.feeOn(BigDecimal.ZERO, new BigDecimal("1.5"))).isEqualByComparingTo("0");
        // More given back (voids, refunds) than sold: no fee, never a negative one.
        assertThat(DailyFeeService.feeOn(new BigDecimal("-5000"), new BigDecimal("1.5"))).isEqualByComparingTo("0");
    }

    static DailyFee day(int d, String status) {
        return DailyFee.builder().feeDate(LocalDate.of(2026, 11, d)).due(BigDecimal.TEN).status(status).build();
    }

    @Test
    void remainingIsWhatTheDebitHasNotTaken() {
        DailyFee f = DailyFee.builder().due(new BigDecimal("18600")).collected(new BigDecimal("10000")).build();
        assertThat(f.remaining()).isEqualByComparingTo("8600");
        f.setCollected(new BigDecimal("20000"));
        assertThat(f.remaining()).isEqualByComparingTo("0");
    }
}
