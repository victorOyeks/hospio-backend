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
public class BusinessStageChangedEvent extends HospioEvent {
    private String fromStage;
    private String toStage;

    @Override
    public String summary() {
        return "Business moved from " + fromStage + " to " + toStage;
    }
}
