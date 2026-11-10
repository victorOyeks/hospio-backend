package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationPrefsTest {

    @Test
    void everyCategoryIsOnUntilTurnedOff() {
        assertThat(NotificationPrefs.email(null)).containsOnlyKeys(NotificationPrefs.CATEGORIES).doesNotContainValue(false);
        String off = NotificationPrefs.apply(null, Map.of("money", false, "stock", false));
        assertThat(off).isEqualTo("money,stock");
        assertThat(NotificationPrefs.email(off)).containsEntry("money", false).containsEntry("stock", false).containsEntry("bookings", true);
        assertThat(NotificationPrefs.apply(off, Map.of("money", true))).isEqualTo("stock");
        assertThat(NotificationPrefs.apply("stock", Map.of("stock", true))).isNull();
        Map<String, Boolean> withNull = new HashMap<>();
        withNull.put("reports", null);
        assertThat(NotificationPrefs.apply("stock", withNull)).isEqualTo("stock");
    }

    @Test
    void unknownCategoriesAreRefusedAndOldStoredOnesIgnored() {
        assertThatThrownBy(() -> NotificationPrefs.apply(null, Map.of("sms", false))).isInstanceOf(BadRequestException.class);
        assertThat(NotificationPrefs.email("gone,money")).containsEntry("money", false).hasSize(7);
    }
}
