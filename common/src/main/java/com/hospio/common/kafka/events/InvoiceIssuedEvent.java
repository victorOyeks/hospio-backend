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
public class InvoiceIssuedEvent extends HospioEvent {
    private String invoiceId;
    private String organizationId;
    private String organizationName;
    private java.math.BigDecimal amount;
    private java.time.LocalDate dueDate;

    @Override
    public String summary() {
        return "Invoice of " + amount + " sent to " + organizationName;
    }
}
