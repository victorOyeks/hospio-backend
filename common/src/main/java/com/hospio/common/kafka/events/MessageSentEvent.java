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
public class MessageSentEvent extends HospioEvent {
    private String messageId;
    private String channel;
    private String to;
    private String subject;

    @Override
    public String summary() {
        return channel + " message sent: " + subject;
    }
}
