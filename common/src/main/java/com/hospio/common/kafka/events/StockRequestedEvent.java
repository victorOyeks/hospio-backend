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
public class StockRequestedEvent extends HospioEvent {
    private String requestId;
    private String fromTeam;
    private String storeId;
    private String lines;
    private String requestedBy;

    @Override
    public String summary() {
        return fromTeam + " asked the store for " + lines;
    }
}
