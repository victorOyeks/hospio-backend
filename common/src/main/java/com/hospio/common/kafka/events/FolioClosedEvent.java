package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FolioClosedEvent extends HospioEvent {
    private String folioId;
    private String bookingId;
    private String guestName;
    private java.math.BigDecimal total;
    private java.math.BigDecimal paid;
    private java.math.BigDecimal balance;

    @Override
    public String summary() {
        return "Folio closed for " + guestName + " · balance " + balance;
    }
}
