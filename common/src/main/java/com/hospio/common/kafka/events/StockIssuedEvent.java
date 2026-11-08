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
public class StockIssuedEvent extends HospioEvent {
    private String requestId;
    private String toTeam;
    private Integer itemCount;

    @Override
    public String summary() {
        return itemCount + " items issued to " + toTeam;
    }
}
