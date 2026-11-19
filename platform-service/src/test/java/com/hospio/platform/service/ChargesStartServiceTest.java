package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.platform.entity.DailyFee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/plan-monthly-invoices.md, "Charges start on": free before the date (or with none), billed from it; only open days move. */
class ChargesStartServiceTest {

    static DailyFee day(int d, String sales, String fee, String device) {
        DailyFee f = DailyFee.builder().businessId("b1").hotelId("h1").hotelName("Ikeja").feeDate(LocalDate.of(2026, 11, d)).salesTotal(new BigDecimal(sales))
                .rate(new BigDecimal("1.5")).fee(new BigDecimal(fee)).deviceFee(new BigDecimal(device))
                .due(new BigDecimal(fee).add(new BigDecimal(device))).status(DailyFee.NO_FEE).build();
        ChargesStartService.apply(f, LocalDate.of(2026, 11, 1)); // as worked out while billed
        return f;
    }

    @Test
    void noDateIsFreeAndADateBillsFromThatDayOn() {
        LocalDate start = LocalDate.of(2026, 11, 15);
        assertThat(ChargesStartService.free(null, LocalDate.of(2026, 11, 20))).isTrue();
        assertThat(ChargesStartService.free(start, LocalDate.of(2026, 11, 14))).isTrue();
        assertThat(ChargesStartService.free(start, start)).isFalse();
        assertThat(ChargesStartService.state(null, start)).isEqualTo("FREE");
        assertThat(ChargesStartService.state(start, LocalDate.of(2026, 11, 1))).isEqualTo("STARTS");
        assertThat(ChargesStartService.state(start, start)).isEqualTo("BILLING");
        assertThat(ChargesStartService.label(null)).isEqualTo("Free");
        assertThat(ChargesStartService.label(LocalDate.of(2027, 3, 1))).isEqualTo("Billing from 1 Mar 2027");
    }

    @Test
    void aFreeDayKeepsItsFeeForShowButChargesOnlyTheDeviceShare() {
        DailyFee f = day(10, "200000", "3000", "0");
        assertThat(f.getStatus()).isEqualTo(DailyFee.ACCRUED);
        assertThat(ChargesStartService.apply(f, null)).isTrue();
        assertThat(f.getStatus()).isEqualTo(DailyFee.FREE);
        assertThat(f.getFee()).isEqualByComparingTo("0");
        assertThat(f.getWaivedFee()).isEqualByComparingTo("3000");
        assertThat(f.getDue()).isEqualByComparingTo("0");

        DailyFee withDevice = day(11, "100000", "1500", "2500");
        ChargesStartService.apply(withDevice, LocalDate.of(2026, 12, 1));
        assertThat(withDevice.getStatus()).isEqualTo(DailyFee.FREE);
        assertThat(withDevice.getDue()).as("devices are not free").isEqualByComparingTo("2500");

        // Back to billed: the fee returns whole, nothing is lost or doubled.
        assertThat(ChargesStartService.apply(f, LocalDate.of(2026, 11, 10))).isTrue();
        assertThat(f.getStatus()).isEqualTo(DailyFee.ACCRUED);
        assertThat(f.getFee()).isEqualByComparingTo("3000");
        assertThat(f.getWaivedFee()).isEqualByComparingTo("0");
        assertThat(ChargesStartService.apply(f, LocalDate.of(2026, 11, 10))).as("same answer, nothing to save").isFalse();

        DailyFee nothingSold = day(12, "0", "0", "0");
        ChargesStartService.apply(nothingSold, null);
        assertThat(nothingSold.getStatus()).isEqualTo(DailyFee.NO_FEE);
    }

    @Test
    void thePreviewCountsWhatMovesAndAnEarlierDateNeedsConfirming() {
        DailyFee d10 = day(10, "200000", "3000", "0");
        DailyFee d20 = day(20, "100000", "1500", "0");
        ChargesStartService.apply(d10, null);
        ChargesStartService.apply(d20, null);
        // Free now; a date of 15 Nov bills the 20th only.
        ChargesStartService.Preview p = ChargesStartService.preview(null, LocalDate.of(2026, 11, 15), List.of(d10, d20));
        assertThat(p.earlier()).isTrue();
        assertThat(p.toBill().days()).isEqualTo(1);
        assertThat(p.toBill().fee()).isEqualByComparingTo("1500");
        assertThat(p.toFree().days()).isZero();
        // Billed from 1 Nov; moving to 15 Nov frees the 10th: not "earlier", no confirm needed.
        ChargesStartService.apply(d10, LocalDate.of(2026, 11, 1));
        ChargesStartService.apply(d20, LocalDate.of(2026, 11, 1));
        ChargesStartService.Preview later = ChargesStartService.preview(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15), List.of(d10, d20));
        assertThat(later.earlier()).isFalse();
        assertThat(later.toFree().days()).isEqualTo(1);
        assertThat(later.toFree().fee()).isEqualByComparingTo("3000");
        assertThat(later.message()).contains("Invoices already issued are never changed");
    }

    @Test
    void theOwnerSeesFreeOrWhenChargesStart() {
        LocalDate today = LocalDate.of(2026, 11, 19);
        assertThat(ChargesStartService.ownerView(null, new BigDecimal("1.5"), today).title()).isEqualTo("Free: no charges until Hospio360 tells you");
        assertThat(ChargesStartService.ownerView(LocalDate.of(2027, 3, 1), new BigDecimal("1.50"), today).title())
                .isEqualTo("Charges start on 1 March 2027 — 1.5% of daily sales, invoiced monthly");
        assertThat(ChargesStartService.ownerView(LocalDate.of(2026, 11, 1), new BigDecimal("1.2"), today).state()).isEqualTo("BILLING");
    }

    @Test
    void theHistoryReadsTheDatesBackFromTheChangeRecord() {
        assertThat(ChargesStartService.dateIn(ChargesStartService.json(LocalDate.of(2027, 3, 1)))).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(ChargesStartService.dateIn(ChargesStartService.json(null))).isNull();
    }
}
