package com.hospio.reservation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Check out. The front end reads the folio balance first; a positive balance is allowed only with
 * {@code allowPendingPayment} (a pending POS payment, or a manager's say-so). Summary channels are
 * EMAIL and/or WHATSAPP, whichever the guest gave.
 */
public record CheckOutRequest(BigDecimal folioBalance, Boolean allowPendingPayment, List<String> summaryChannels) {
}
