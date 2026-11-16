package com.hospio.common.kafka.events;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A stay's bill as its folios have it now, summed over every folio of the booking: what was charged (tax in, discounts
 * out), what was paid (refunds out) and what is still owed. reservation-service keeps a copy on the booking so every list,
 * total and "due" reads the same numbers as the folio.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FolioTotalsEvent extends HospioEvent {
    private String bookingId;
    private BigDecimal total;
    private BigDecimal paid;
    private BigDecimal balance;

    @Override
    public String summary() {
        return "Bill for booking " + bookingId + " · balance " + balance;
    }
}
