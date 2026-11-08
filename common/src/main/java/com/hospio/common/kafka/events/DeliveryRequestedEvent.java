package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** A message to send to a person (email, SMS or WhatsApp). messaging-service consumes these once a provider is connected. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DeliveryRequestedEvent extends HospioEvent {
    private String channel;
    private String to;
    private String subject;
    private String text;
    private String kind;
    private String ref;

    @Override
    public String summary() {
        return kind + " to " + to + " by " + channel;
    }
}
