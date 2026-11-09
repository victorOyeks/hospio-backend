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
    /** ISSUED, PART_ISSUED, DECLINED or CLOSED (null from older senders: issued). */
    private String status;
    /** What was issued ("5 kg Rice, 2 litres Oil"), or the reason it was declined. */
    private String note;

    @Override
    public String summary() {
        return itemCount + " items issued to " + toTeam;
    }
}
