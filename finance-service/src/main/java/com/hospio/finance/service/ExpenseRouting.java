package com.hospio.finance.service;

import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;

/**
 * Who has to approve an expense, from the hotel's APPROVAL_LIMITS: up to the manager's limit a manager's (or owner's)
 * own spend is simply recorded and staff spend needs a manager; above it the owner has to approve unless the owner is the
 * one spending. An owner ceiling above zero is the most the hotel will spend in one go; 0 means no ceiling.
 */
public final class ExpenseRouting {

    private ExpenseRouting() {
    }

    public enum Caller { STAFF, MANAGER, OWNER }

    /** {@code approverLevel} is null when the expense is recorded straight away. */
    public record Route(boolean needsApproval, String approverLevel) {
    }

    public static Route route(BigDecimal amount, BigDecimal managerLimit, BigDecimal ownerLimit, Caller caller) {
        if (ownerLimit != null && ownerLimit.signum() > 0 && amount.compareTo(ownerLimit) > 0) {
            throw new BadRequestException("That is over the " + ownerLimit.stripTrailingZeros().toPlainString() + " ceiling this hotel has set for a single expense");
        }
        if (amount.compareTo(managerLimit) > 0) {
            return caller == Caller.OWNER ? new Route(false, null) : new Route(true, "OWNER");
        }
        return caller == Caller.STAFF ? new Route(true, "MANAGER") : new Route(false, null);
    }
}
