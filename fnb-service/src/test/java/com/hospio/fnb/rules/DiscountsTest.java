package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Discounts by hand: a line's, the bill's share per line, and the percentage the limit is judged on. */
class DiscountsTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static Discounts.Line line(String id, String gross, String happy, String manual) {
        return new Discounts.Line(id, n(gross), n(happy), n(manual));
    }

    @Test
    void aLineDiscountIsAPercentageOfWhatHappyHourLeftOrAnAmount() {
        assertThat(Discounts.lineDiscount(n("3000"), n("10"), null)).isEqualByComparingTo("300.00");
        assertThat(Discounts.lineDiscount(n("3000"), null, n("450"))).isEqualByComparingTo("450.00");
        assertThat(Discounts.lineDiscount(n("3000"), null, n("9000"))).isEqualByComparingTo("3000.00");
    }

    @Test
    void aBillPercentageComesOffEveryLine() {
        List<Discounts.Line> lines = List.of(line("a", "3000", "0", "0"), line("b", "5000", "1000", "0"));
        Map<String, BigDecimal> shares = Discounts.billShares(lines, n("10"), null);
        assertThat(shares.get("a")).isEqualByComparingTo("300.00");
        assertThat(shares.get("b")).isEqualByComparingTo("400.00");
        assertThat(Discounts.highestPct(lines, shares)).isEqualByComparingTo("10.00");
    }

    @Test
    void aBillAmountIsSharedToTheKoboAndNeverMoreThanTheBill() {
        List<Discounts.Line> lines = List.of(line("a", "1000", "0", "0"), line("b", "1000", "0", "0"), line("c", "1000", "0", "0"));
        Map<String, BigDecimal> shares = Discounts.billShares(lines, null, n("100"));
        assertThat(shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(shares.get("c")).isEqualByComparingTo("33.34");
        Map<String, BigDecimal> all = Discounts.billShares(lines, null, n("99999"));
        assertThat(all.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3000.00");
    }

    @Test
    void theLimitLooksAtTheLineGivenTheMostByHand() {
        // 10 % on one line and 10 % on the bill stack to 19 % of that line: over a 10 % limit.
        List<Discounts.Line> lines = List.of(line("a", "2000", "0", "200"), line("b", "2000", "0", "0"));
        Map<String, BigDecimal> shares = Discounts.billShares(lines, n("10"), null);
        assertThat(Discounts.highestPct(lines, shares)).isEqualByComparingTo("19.00");
        assertThat(Discounts.within(n("19.00"), n("10"))).isFalse();
        assertThat(Discounts.within(n("10.00"), n("10"))).isTrue();
        assertThat(Discounts.within(n("10.04"), n("10"))).isTrue();
        assertThat(Discounts.within(n("5"), null)).isFalse();
    }
}
