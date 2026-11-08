package com.hospio.hotelconfig.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hospio.common.setup.SetupCatalogue;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Setup completeness counts only catalogue keys whose value differs from the default. */
class AdminOverviewServiceTest {

    private static int totalKeys() {
        return SetupCatalogue.SECTIONS.values().stream().mapToInt(Map::size).sum();
    }

    @Test
    void defaultsScoreZeroAndEveryChangedKeyScoresFull() {
        Map<String, Map<String, JsonNode>> same = new HashMap<>();
        SetupCatalogue.SECTIONS.forEach((s, v) -> same.put(s, new HashMap<>(v)));
        assertThat(AdminOverviewServiceTest.pct(same)).isZero();

        Map<String, Map<String, JsonNode>> all = new HashMap<>();
        SetupCatalogue.SECTIONS.forEach((s, v) -> {
            Map<String, JsonNode> m = new HashMap<>();
            v.forEach((k, d) -> m.put(k, JsonNodeFactory.instance.textNode("changed-" + k)));
            all.put(s, m);
        });
        assertThat(AdminOverviewServiceTest.pct(all)).isEqualTo(100);
    }

    @Test
    void oneChangedKeyAndUnknownKeysCountProportionally() {
        Map<String, Map<String, JsonNode>> stored = new HashMap<>();
        stored.put(SetupCatalogue.MESSAGING, new HashMap<>(Map.of("whatsappNumber", JsonNodeFactory.instance.textNode("+2348010000000"),
                "notInCatalogue", JsonNodeFactory.instance.textNode("x"))));
        assertThat(pct(stored)).isEqualTo((int) Math.round(100.0 / totalKeys()));
        assertThat(pct(Map.of())).isZero();
    }

    private static int pct(Map<String, Map<String, JsonNode>> stored) {
        return AdminOverviewService.setupCompletePct(stored);
    }
}
