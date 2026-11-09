package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.finance.service.ExpenseRouting.Caller;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Expense routing from the hotel's limits: staff need a manager, big spend needs the owner, 0 owner limit means no ceiling. */
class ExpenseRoutingTest {

    private static final BigDecimal MANAGER_LIMIT = new BigDecimal("200000");
    private static final BigDecimal NO_CEILING = BigDecimal.ZERO;

    private static ExpenseRouting.Route route(String amount, Caller caller) {
        return ExpenseRouting.route(new BigDecimal(amount), MANAGER_LIMIT, NO_CEILING, caller);
    }

    @Test
    void managersOwnSpendWithinTheLimitIsRecorded() {
        assertThat(route("150000", Caller.MANAGER).needsApproval()).isFalse();
        assertThat(route("200000", Caller.MANAGER).needsApproval()).isFalse();
    }

    @Test
    void staffSpendWithinTheLimitNeedsAManager() {
        ExpenseRouting.Route r = route("5000", Caller.STAFF);
        assertThat(r.needsApproval()).isTrue();
        assertThat(r.approverLevel()).isEqualTo("MANAGER");
    }

    @Test
    void anythingOverTheManagersLimitNeedsTheOwnerWhoeverAsks() {
        assertThat(route("200000.01", Caller.MANAGER).approverLevel()).isEqualTo("OWNER");
        assertThat(route("900000", Caller.STAFF).approverLevel()).isEqualTo("OWNER");
    }

    @Test
    void theOwnersOwnSpendIsRecordedAtAnyAmountWithNoCeiling() {
        assertThat(route("5000000", Caller.OWNER).needsApproval()).isFalse();
    }

    @Test
    void aCeilingAboveZeroRefusesWhatGoesOverIt() {
        BigDecimal ceiling = new BigDecimal("1000000");
        assertThat(ExpenseRouting.route(new BigDecimal("1000000"), MANAGER_LIMIT, ceiling, Caller.OWNER).needsApproval()).isFalse();
        assertThatThrownBy(() -> ExpenseRouting.route(new BigDecimal("1000001"), MANAGER_LIMIT, ceiling, Caller.OWNER)).isInstanceOf(BadRequestException.class);
    }
}
