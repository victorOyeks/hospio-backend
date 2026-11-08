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
public class SettingChangedEvent extends HospioEvent {
    private String section;
    private String key;
    private String oldValue;
    private String newValue;

    @Override
    public String summary() {
        return "Hotel setup · " + section + " · " + key + " changed";
    }
}
