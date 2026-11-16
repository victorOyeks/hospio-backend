package com.hospio.common.kafka.events;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A super admin saved or cleared a provider's settings in Settings · Integrations (platform-wide, no business). Names the fields only:
 * a value, secret or not, is never part of the event or the activity log.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class IntegrationChangedEvent extends HospioEvent {
    private String provider;
    private String providerLabel;
    /** Fields given a new value. */
    private List<String> saved;
    /** Fields cleared: the server's environment applies to them again. */
    private List<String> cleared;

    @Override
    public String summary() {
        StringBuilder s = new StringBuilder("Settings · Integrations · ").append(providerLabel).append(":");
        if (saved != null && !saved.isEmpty()) {
            s.append(" ").append(String.join(", ", saved)).append(" saved");
        }
        if (cleared != null && !cleared.isEmpty()) {
            s.append(saved != null && !saved.isEmpty() ? ";" : "").append(" ").append(String.join(", ", cleared))
                    .append(" cleared (the server's values apply)");
        }
        return s.toString();
    }
}
