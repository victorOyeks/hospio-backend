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
public class HandoverSentEvent extends HospioEvent {
    private String ownerUserId;
    private String ownerEmail;

    @Override
    public String summary() {
        return "Handover pack sent to " + ownerEmail;
    }
}
