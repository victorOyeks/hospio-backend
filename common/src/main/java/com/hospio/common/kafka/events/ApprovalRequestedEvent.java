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
public class ApprovalRequestedEvent extends HospioEvent {
    private String approvalId;
    private String sourceModule;
    private String sourceRef;
    private String kind;
    private java.math.BigDecimal amount;
    private String requesterId;
    private String requesterName;
    private String approverLevel;
    private String description;

    @Override
    public String summary() {
        return requesterName + " asked for approval: " + description;
    }
}
