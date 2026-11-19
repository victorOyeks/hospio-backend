package com.hospio.common.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * Guest-message invoices folded into Hospio360's monthly invoice (docs/plan-monthly-invoices.md): messaging-service still counts and
 * prices each hotel's messages per month (MSG-…), platform-service bills them on the business's monthly invoice and tells messaging when
 * that invoice is paid (or voided).
 */
public final class MessageBillingDtos {

    private MessageBillingDtos() {
    }

    /** One hotel's guest-message invoice not on a monthly invoice yet. {@code smsCount} is history: above 0 only on an older invoice. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToBill(String id, String number, String month, String hotelId, String hotelName, int emailCount, int smsCount, int whatsappCount,
                         BigDecimal total) {
    }

    /** Puts these message invoices on the monthly invoice (consolidate), settles them when it is paid, or releases them on a void. */
    public record Link(String monthlyInvoiceId, String monthlyInvoiceNumber, List<String> invoiceIds) {
    }
}
