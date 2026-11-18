package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** The owner reviewed the handover pack and accepted it; the business is live. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class HandoverAcceptedEvent extends HospioEvent {
    private String ownerUserId;
    private String businessName;
    /** The business's account manager (a platform admin), told in the app; null when none is assigned. */
    private String accountManagerId;
    private String ownerName;

    @Override
    public String summary() {
        return "Handover accepted for " + businessName;
    }
}
