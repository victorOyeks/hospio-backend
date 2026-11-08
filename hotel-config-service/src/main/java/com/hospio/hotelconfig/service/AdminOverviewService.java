package com.hospio.hotelconfig.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.hotelconfig.dto.HotelOverviewResponse;
import com.hospio.hotelconfig.entity.Setting;
import com.hospio.hotelconfig.repository.SettingRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only figures for the platform admin portal; the caller is the platform, so every hotel is visible. */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final SettingRepository settings;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public HotelOverviewResponse hotelOverview(String hotelId) {
        Map<String, Map<String, JsonNode>> stored = new java.util.HashMap<>();
        for (Setting s : settings.findByHotelId(hotelId)) {
            stored.computeIfAbsent(s.getSection(), k -> new java.util.HashMap<>()).put(s.getKey(), parse(s.getValue()));
        }
        Map<String, Map<String, JsonNode>> merged = SetupCatalogue.merge(stored);
        String whatsapp = text(merged, SetupCatalogue.MESSAGING, "whatsappNumber");
        String pos = text(merged, SetupCatalogue.POS, "provider");
        return new HotelOverviewResponse(whatsapp, pos, setupCompletePct(stored));
    }

    /** Share of catalogue keys whose stored value differs from the default; keys outside the catalogue do not count. */
    static int setupCompletePct(Map<String, Map<String, JsonNode>> stored) {
        int total = 0;
        int changed = 0;
        for (Map.Entry<String, Map<String, JsonNode>> section : SetupCatalogue.SECTIONS.entrySet()) {
            Map<String, JsonNode> values = stored.getOrDefault(section.getKey(), Map.of());
            for (Map.Entry<String, JsonNode> key : section.getValue().entrySet()) {
                total++;
                JsonNode value = values.get(key.getKey());
                if (value != null && !value.isNull() && !value.equals(key.getValue())) {
                    changed++;
                }
            }
        }
        return total == 0 ? 0 : (int) Math.round(changed * 100.0 / total);
    }

    private static String text(Map<String, Map<String, JsonNode>> merged, String section, String key) {
        JsonNode v = merged.getOrDefault(section, Map.of()).get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isTextual() ? v.asText() : v.toString();
        return s.isBlank() ? null : s;
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }
}
