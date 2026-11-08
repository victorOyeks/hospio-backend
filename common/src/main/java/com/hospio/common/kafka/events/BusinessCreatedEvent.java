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
public class BusinessCreatedEvent extends HospioEvent {
    private String name;
    private String ownerUserId;
    private String stage;
    /** SELF_SERVE (the owner signed up) or BACK_OFFICE (H.M.S created it). */
    private String source;
    /** The marketer profile that referred the business, when any. */
    private String marketerId;
    /** The marketer's referral this business came from, when any. */
    private String referralId;

    @Override
    public String summary() {
        return "Business " + name + " created";
    }
}
