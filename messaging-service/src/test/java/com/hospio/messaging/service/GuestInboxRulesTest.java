package com.hospio.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Template placeholders: filled from the stay, left in place (and listed) when the stay doesn't have them. */
class GuestInboxRulesTest {

    @Test
    void placeholdersAreFilledFromTheStay() {
        Map<String, String> v = new HashMap<>();
        v.put("hotel", "Royal Suites Ikeja");
        GuestInboxService.stay(v, "Ifeoma Okafor", "A325", "Deluxe", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16), "HB6980XK");
        List<String> missing = new ArrayList<>();
        String out = GuestInboxService.fillText("Hello {{first_name}} ({{guest}}), room {{room}} ({{room_type}}) at {{hotel}} from {{check_in}} to {{check_out}},"
                + " {{nights}} nights, ref {{ reference }}. Old style {guest}.", v, missing);
        assertThat(out).isEqualTo("Hello Ifeoma (Ifeoma Okafor), room A325 (Deluxe) at Royal Suites Ikeja from Sat 14 Nov 2026 to Mon 16 Nov 2026,"
                + " 2 nights, ref HB6980XK. Old style Ifeoma Okafor.");
        assertThat(missing).isEmpty();
    }

    @Test
    void missingValuesStayAndAreListed() {
        Map<String, String> v = new HashMap<>();
        v.put("hotel", "H");
        GuestInboxService.stay(v, "Ada", null, null, null, null, null);
        List<String> missing = new ArrayList<>();
        String out = GuestInboxService.fillText("Hi {{guest}}, room {{room}} on {{check_in}}", v, missing);
        assertThat(out).isEqualTo("Hi Ada, room {{room}} on {{check_in}}");
        assertThat(missing).containsExactly("room", "check_in");
    }

    @Test
    void unknownPlaceholdersAreRefused() {
        assertThat(GuestInboxService.unknownPlaceholders("Hi {{guest}} {{balance}} {{Room}}")).containsExactly("{{balance}}");
    }
}
