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
public class PosOfflineEvent extends HospioEvent {
    private String terminalId;
    private String terminalName;
    private String role;

    @Override
    public String summary() {
        return "POS " + terminalName + " is offline";
    }
}
