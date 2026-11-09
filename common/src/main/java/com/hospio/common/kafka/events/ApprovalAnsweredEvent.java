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
public class ApprovalAnsweredEvent extends HospioEvent {
    private String approvalId;
    private String sourceModule;
    private String sourceRef;
    private String answeredBy;
    private String answeredByName;
    private String note;

    @Override
    public String summary() {
        return answeredByName + " answered the question on an approval";
    }
}
