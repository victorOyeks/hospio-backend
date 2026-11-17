package com.hospio.folio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.billing.DailySales;
import com.hospio.folio.entity.FolioLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The folio's daily sales: one night per day, voids taken back on their day, every day final once over. */
class SalesReportTest {

    static final LocalDate D1 = LocalDate.of(2026, 11, 10);
    static final LocalDate D2 = D1.plusDays(1);
    static final LocalDate D3 = D1.plusDays(2);

    static Instant at(LocalDate d, int hour) {
        return d.atTime(hour, 0).atZone(SalesReport.LAGOS).toInstant();
    }

    static FolioLine room(String amount, LocalDate from, LocalDate to, Instant posted) {
        return FolioLine.builder().kind(FolioLine.Kind.CHARGE).amount(new BigDecimal(amount)).taxAmount(BigDecimal.ZERO)
                .serviceFrom(from).serviceTo(to).postedAt(posted).build();
    }

    static FolioLine charge(String amount, Instant posted) {
        return FolioLine.builder().kind(FolioLine.Kind.CHARGE).amount(new BigDecimal(amount)).taxAmount(new BigDecimal("750")).postedAt(posted).build();
    }

    static BigDecimal line(DailySales s, String source) {
        return s.lines().stream().filter(l -> l.source().equals(source)).findFirst().orElseThrow().amount();
    }

    static BigDecimal total(List<FolioLine> lines, LocalDate day) {
        return SalesReport.tally(lines, day).lines().stream().map(DailySales.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aStayCountsOneNightPerDay() {
        FolioLine r = room("100000", D1, D1.plusDays(3), at(D1, 14));
        assertThat(line(SalesReport.tally(List.of(r), D1), "FOLIO_ROOM")).isEqualByComparingTo("33333.33");
        assertThat(line(SalesReport.tally(List.of(r), D2), "FOLIO_ROOM")).isEqualByComparingTo("33333.33");
        assertThat(line(SalesReport.tally(List.of(r), D3), "FOLIO_ROOM")).isEqualByComparingTo("33333.34");
    }

    @Test
    void aNightPostedLateCountsOnTheDayItWasPosted() {
        // Checked in at 1am on D2 for a stay from D1: night D1 was over by then, so it counts on D2 with D2's night.
        FolioLine r = room("70000", D1, D3, at(D2, 1));
        assertThat(line(SalesReport.tally(List.of(r), D1), "FOLIO_ROOM")).isEqualByComparingTo("0");
        assertThat(line(SalesReport.tally(List.of(r), D2), "FOLIO_ROOM")).isEqualByComparingTo("70000");
    }

    @Test
    void aVoidTakesBackOnlyWhatAlreadyCounted() {
        FolioLine r = room("90000", D1, D1.plusDays(3), at(D1, 14));
        r.setVoided(true);
        r.setVoidedAt(at(D3, 10));
        // Nights D1 and D2 counted; on D3 the night doesn't count and the two earlier nights come back as a void.
        DailySales d3 = SalesReport.tally(List.of(r), D3);
        assertThat(line(d3, "FOLIO_ROOM")).isEqualByComparingTo("0");
        assertThat(line(d3, "FOLIO_VOIDS")).isEqualByComparingTo("-60000");
        BigDecimal overall = total(List.of(r), D1).add(total(List.of(r), D2)).add(total(List.of(r), D3));
        assertThat(overall).isEqualByComparingTo("0");
    }

    @Test
    void aChargeVoidedTheSameDayNeverCounts() {
        FolioLine c = charge("5000", at(D1, 12));
        c.setVoided(true);
        c.setVoidedAt(at(D1, 13));
        assertThat(total(List.of(c), D1)).isEqualByComparingTo("0");
    }

    @Test
    void extrasCountWithoutTheirTaxAndDiscountsComeOff() {
        FolioLine c = charge("5000", at(D1, 12));
        FolioLine d = FolioLine.builder().kind(FolioLine.Kind.DISCOUNT).amount(new BigDecimal("1000")).postedAt(at(D1, 15)).build();
        DailySales s = SalesReport.tally(List.of(c, d), D1);
        assertThat(line(s, "FOLIO_EXTRAS")).isEqualByComparingTo("5000");
        assertThat(line(s, "FOLIO_DISCOUNTS")).isEqualByComparingTo("-1000");
    }

    @Test
    void fewerNightsCreditTheNightsTakenOff() {
        // Booked D1..D1+4 (4 nights at 10,000), shortened on D2 to leave D3: -20,000 covering nights D3 and D3+1.
        FolioLine r = room("40000", D1, D1.plusDays(4), at(D1, 14));
        FolioLine adj = room("-20000", D3, D1.plusDays(4), at(D2, 9));
        assertThat(total(List.of(r, adj), D3)).isEqualByComparingTo("0");
        assertThat(total(List.of(r, adj), D2)).isEqualByComparingTo("10000");
    }

    @Test
    void theGuestServiceChargeIsNeverCountedAsSales() {
        java.time.LocalDate d = java.time.LocalDate.of(2026, 11, 16);
        FolioLine bar = charge("10000", at(d, 20));
        FolioLine sc = FolioLine.builder().kind(FolioLine.Kind.CHARGE).category("SERVICE_CHARGE").amount(new BigDecimal("150")).taxAmount(BigDecimal.ZERO)
                .postedAt(at(d, 20)).build();
        assertThat(total(List.of(bar, sc), d)).isEqualByComparingTo("10000");
    }
}
