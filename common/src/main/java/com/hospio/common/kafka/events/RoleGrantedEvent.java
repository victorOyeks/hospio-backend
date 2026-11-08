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
public class RoleGrantedEvent extends HospioEvent {
    private String userId;
    private String userName;
    private String role;
    private String level;

    @Override
    public String summary() {
        return userName + " was given " + role + " (" + level + ")";
    }
}
