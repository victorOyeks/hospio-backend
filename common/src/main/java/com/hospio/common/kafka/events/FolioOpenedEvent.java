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
public class FolioOpenedEvent extends HospioEvent {
    private String folioId;
    private String bookingId;
    private String guestName;

    @Override
    public String summary() {
        return "Folio opened for " + guestName;
    }
}
