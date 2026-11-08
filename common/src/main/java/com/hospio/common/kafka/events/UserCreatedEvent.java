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
public class UserCreatedEvent extends HospioEvent {
    private String userId;
    private String fullName;
    private String email;

    @Override
    public String summary() {
        return "Login created for " + fullName;
    }
}
