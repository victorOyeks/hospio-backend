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
public class ChargePostedEvent extends HospioEvent {
    private String folioId;
    private String bookingId;
    private String roomId;
    private String roomNumber;
    private String sourceModule;
    private String sourceRef;
    private String description;
    private java.math.BigDecimal amount;
    private java.math.BigDecimal taxAmount;
    private String category;

    @Override
    public String summary() {
        return sourceModule + " charged " + amount + " to room " + roomNumber + ": " + description;
    }
}
