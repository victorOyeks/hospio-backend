package com.hospio.folio.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Create an invoice for an organization's stays in a period; the amount is the sum of their folios' balances. */
public record InvoiceRequest(@NotNull LocalDate periodFrom, @NotNull LocalDate periodTo, LocalDate dueDate) {
}
