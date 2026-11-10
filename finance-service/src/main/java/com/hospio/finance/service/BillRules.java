package com.hospio.finance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.util.Money;
import com.hospio.finance.entity.Bill;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/** The rules of a bill that need no database: what a valid bill is, what paying it records, when the next one is due. */
public final class BillRules {

    private BillRules() {
    }

    public static final String DEFAULT_PAID_FROM = "BANK";

    /** The fields a bill is saved with, checked: a payee, a category, an amount above zero, a due date; repeats NONE, WEEKLY or MONTHLY. */
    public record Clean(String payee, String category, BigDecimal amount, LocalDate dueDate, Bill.Repeats repeats, String note) {
    }

    public static Clean check(String payee, String category, BigDecimal amount, LocalDate dueDate, String repeats, String note) {
        String p = payee == null ? "" : payee.trim();
        if (p.isEmpty()) {
            throw new BadRequestException("Say who the bill is from");
        }
        if (p.length() > 150) {
            throw new BadRequestException("Keep the payee under 150 characters");
        }
        if (category == null || ExpenseCategories.codeOf(category).isEmpty()) {
            throw new BadRequestException("Pick a category");
        }
        if (!Money.isPositive(amount)) {
            throw new BadRequestException("Enter an amount above zero");
        }
        if (dueDate == null) {
            throw new BadRequestException("Give the due date");
        }
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > 500) {
            throw new BadRequestException("Keep the note under 500 characters");
        }
        return new Clean(p, category.trim(), Money.of(amount), dueDate, repeats(repeats), n);
    }

    public static Bill.Repeats repeats(String repeats) {
        if (repeats == null || repeats.isBlank()) {
            return Bill.Repeats.NONE;
        }
        try {
            return Bill.Repeats.valueOf(repeats.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("A bill repeats NONE, WEEKLY or MONTHLY");
        }
    }

    /** BANK (the default), TILL or CASH. */
    public static String paidFrom(String paidFrom) {
        if (paidFrom == null || paidFrom.isBlank()) {
            return DEFAULT_PAID_FROM;
        }
        String p = paidFrom.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("BANK") && !p.equals("TILL") && !p.equals("CASH")) {
            throw new BadRequestException("Pay a bill from BANK, TILL or CASH");
        }
        return p;
    }

    /** The next due date of a repeating bill: a week later, or the same day next month (the 31st falls on the month's last day). */
    public static LocalDate nextDue(LocalDate due, Bill.Repeats repeats) {
        return switch (repeats) {
            case WEEKLY -> due.plusWeeks(1);
            case MONTHLY -> due.plusMonths(1);
            default -> null;
        };
    }

    /** What the expense of a paid bill says: "Bill · Ikeja Electric" and the note when there is one. */
    public static String expenseDescription(Bill b) {
        String d = "Bill · " + b.getPayee() + (b.getNote() == null ? "" : " · " + b.getNote());
        return d.length() > 500 ? d.substring(0, 500) : d;
    }

    /** Only an unpaid bill can be changed, paid or cancelled; the refusal says why. */
    public static void requireUnpaid(Bill b, String doing) {
        if (b.getStatus() == Bill.Status.PAID) {
            throw new BadRequestException("paid".equals(doing) ? "This bill is already paid" : "A paid bill can't be " + doing);
        }
        if (b.getStatus() == Bill.Status.CANCELLED) {
            throw new BadRequestException("This bill was cancelled");
        }
    }
}
