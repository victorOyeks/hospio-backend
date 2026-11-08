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
public class AccessChangedEvent extends HospioEvent {
    private String userId;
    private String userName;
    private String change;
    private String role;

    @Override
    public String summary() {
        return userName + ": access " + change + (role == null ? "" : " for " + role);
    }
}
