package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A message to send to a person (email, SMS or WhatsApp). messaging-service consumes these: it records each one and sends the
 * emails through SMTP (SMS and WhatsApp are recorded, not sent yet). The From name is the hotel's name ({@code hotelId}),
 * else the business's, else the platform's; {@code fromName} and {@code replyTo} override that when a sender knows better.
 * {@code html} is an optional ready HTML body; without it messaging-service lays the text out in the shared template.
 * The contract is docs/email.md.
 */
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
    private String fromName;
    private String replyTo;
    private String html;

    @Override
    public String summary() {
        return kind + " to " + to + " by " + channel;
    }
}
