package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.platform.entity.MonthlyInvoicePayment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The letterhead of the invoice and receipt PDFs carries the Hospio360 wordmark (logo Option C) as an image. */
class InvoicePdfTest {

    @Test
    void theReceiptLetterheadEmbedsTheWordmark() {
        MonthlyInvoicePayment p = MonthlyInvoicePayment.builder().invoiceId("i1").receiptNumber("HR-0001").amount(new BigDecimal("1000"))
                .via("TRANSFER").reference("REF1").paidOn(LocalDate.of(2026, 11, 19)).build();
        p.setCreatedAt(Instant.parse("2026-11-19T10:00:00Z"));
        MonthlyInvoiceService.InvoiceView v = new MonthlyInvoiceService.InvoiceView("i1", "HI-0001", "b1", "QA Business", "2026-10",
                "October 2026", null, null, null, null, null, "PAID", "Paid", null, null, null, new BigDecimal("1000"), new BigDecimal("1000"),
                BigDecimal.ZERO, null, null, null, null, null, false, null, null, null, null, null, false, List.of(), List.of(), List.of());

        byte[] pdf = new InvoicePdf().receipt(v, p);

        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("%PDF").contains("/Subtype/Image");
    }
}
