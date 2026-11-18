package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A bill divided to the kobo: the parts always add up to it, the last part taking the remainder. */
class BillSplitTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal sum(List<BigDecimal> parts) {
        return parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void tenThousandBetweenThree() {
        List<BigDecimal> parts = BillSplit.equal(n("10000"), 3);
        assertThat(parts).containsExactly(n("3333.33"), n("3333.33"), n("3333.34"));
        assertThat(sum(parts)).isEqualByComparingTo("10000.00");
    }

    @Test
    void everyNumberOfPeopleAddsUpExactly() {
        for (String total : List.of("0.07", "1", "999.99", "10000", "10537.50", "123456.78")) {
            for (int people = 1; people <= 20; people++) {
                List<BigDecimal> parts = BillSplit.equal(n(total), people);
                assertThat(sum(parts)).as(total + " between " + people).isEqualByComparingTo(total);
                assertThat(parts).allMatch(p -> p.signum() >= 0 && p.scale() == 2);
                // Nobody but the last pays more than a kobo-rounded share.
                assertThat(parts.get(parts.size() - 1).subtract(parts.get(0)).abs()).isLessThan(n("0.20"));
            }
        }
    }

    @Test
    void sharesByWeightAndTheTaxAndServiceChargeInside() {
        // A ₦10,537.50 bill (₦10,000 of items incl. ₦697.67 VAT, plus a ₦537.50 service charge) with items worth 6,000 / 3,000 / 1,000.
        List<BigDecimal> amounts = BillSplit.allocate(n("10537.50"), List.of(n("6000"), n("3000"), n("1000")));
        assertThat(amounts).containsExactly(n("6322.50"), n("3161.25"), n("1053.75"));
        List<BillSplit.Share> shares = BillSplit.shares(amounts, n("697.67"), n("537.50"));
        assertThat(shares.stream().map(BillSplit.Share::tax).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("697.67");
        assertThat(shares.stream().map(BillSplit.Share::serviceCharge).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("537.50");
        assertThat(shares.get(0).tax()).isEqualByComparingTo("418.60");
        assertThat(shares.get(2).tax()).isEqualByComparingTo("69.77");
    }

    @Test
    void theTaxInsideWhatIsLeftOfAPartPaidBill() {
        assertThat(BillSplit.inside(n("697.67"), n("5000"), n("10000"))).isEqualByComparingTo("348.84");
        assertThat(BillSplit.inside(n("697.67"), n("10000"), n("10000"))).isEqualByComparingTo("697.67");
    }
}
