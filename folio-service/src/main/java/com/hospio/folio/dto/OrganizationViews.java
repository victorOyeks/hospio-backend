package com.hospio.folio.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.hospio.folio.entity.Invoice;
import com.hospio.folio.entity.Organization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the organizations screens read in one call instead of one call per account (docs/pagination.md). */
public final class OrganizationViews {

    private OrganizationViews() {
    }

    /**
     * An account with its figures: owed on its stays ({@code outstanding}), staff who can book, stays opened this month (Lagos),
     * stays still open and what they owe, its invoices (open, overdue and what is left on them) and the latest overdue one.
     */
    public record OrganizationSummary(@JsonUnwrapped Organization organization, BigDecimal outstanding, long staffCount, long staysThisMonth,
                                      long stays, long openStays, BigDecimal runningBalance, long invoices, long openInvoices,
                                      BigDecimal invoicesLeft, long overdueInvoices, BigDecimal overdueAmount, String overdueNumber,
                                      LocalDate overdueDueDate) {
    }

    /** An invoice with its account's name; {@code overdue} when it is unpaid past its due date (folio-service never stores OVERDUE). */
    public record InvoiceView(@JsonUnwrapped Invoice invoice, String organizationName, boolean overdue) {
    }

    /** The hotel's accounts at a glance (Organizations KPIs), whatever page or search the list is on. */
    public record OrganizationsOverview(long accounts, long staffCanBook, long staysThisMonth, BigDecimal outstanding, long openInvoices,
                                        long overdueInvoices, BigDecimal overdueAmount, List<String> overdueOrganizations, long onHold,
                                        List<String> onHoldOrganizations) {
    }
}
