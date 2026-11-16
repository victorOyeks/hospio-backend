package com.hospio.hotelconfig.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.setup.HotelSetup;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.setup.SetupSection;
import com.hospio.hotelconfig.dto.HotelOverviewResponse;
import com.hospio.hotelconfig.repository.SettingRepository;
import java.util.List;
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
        HotelSetup stored = HotelSetupService.stored(objectMapper, settings.findByHotelId(hotelId));
        HotelSetup merged = SetupCatalogue.withDefaults(stored);
        return new HotelOverviewResponse(blankToNull(merged.messaging().whatsappNumber()), blankToNull(merged.pos().provider()),
                setupCompletePct(stored));
    }

    /** Share of catalogue keys whose stored value differs from the default; keys outside the catalogue do not count. */
    static int setupCompletePct(HotelSetup stored) {
        int total = SetupCatalogue.keyCount();
        int changed = 0;
        if (stored != null) {
            for (String section : SetupCatalogue.SECTIONS.keySet()) {
                SetupSection values = stored.get(section);
                if (values == null) {
                    continue;
                }
                List<SetupCatalogue.Value> mine = SetupCatalogue.values(values);
                List<SetupCatalogue.Value> defaults = SetupCatalogue.values(SetupCatalogue.defaults(section));
                for (int i = 0; i < mine.size(); i++) {
                    Object value = mine.get(i).value();
                    if (value != null && !SetupCatalogue.sameValue(value, defaults.get(i).value())) {
                        changed++;
                    }
                }
            }
        }
        return total == 0 ? 0 : (int) Math.round(changed * 100.0 / total);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
