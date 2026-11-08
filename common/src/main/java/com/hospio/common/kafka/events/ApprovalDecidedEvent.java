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
public class ApprovalDecidedEvent extends HospioEvent {
    private String approvalId;
    private String sourceModule;
    private String sourceRef;
    private String kind;
    private String decision;
    private String decidedBy;
    private String decidedByName;
    private String note;

    @Override
    public String summary() {
        return decidedByName + " " + decision.toLowerCase() + " " + kind;
    }
}
