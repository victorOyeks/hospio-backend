package com.hospio.finance.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.util.Money;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hotel setup is the source of truth for the limits finance enforces: APPROVAL_LIMITS, PAYMENTS.tillFloat, STAFF.lateMinutes and
 * NIGHT_AUDIT.arrivalsResolvedFirst, read from hotel-config (cached 60 s). The {@code hospio.approvals.*} property is the
 * manager's limit only when hotel-config can't be reached.
 */
@Component
public class HotelSettings {

    public record Limits(BigDecimal managerExpenseLimit, BigDecimal ownerExpenseLimit) {
    }

    private final HotelSettingsReader reader;
    private final BigDecimal fallbackManagerLimit;

    public HotelSettings(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String key,
                         @Value("${hospio.approvals.manager-spend-limit:500000}") BigDecimal fallbackManagerLimit) {
        this.reader = new HotelSettingsReader(url, jwtUtil, key);
        this.fallbackManagerLimit = fallbackManagerLimit;
    }

    public Limits limits(String hotelId) {
        return reader.section(hotelId, SetupCatalogue.APPROVAL_LIMITS).map(n -> new Limits(
                n.path("managerExpenseLimit").isNumber() ? n.path("managerExpenseLimit").decimalValue() : fallbackManagerLimit,
                n.path("ownerExpenseLimit").isNumber() ? n.path("ownerExpenseLimit").decimalValue() : BigDecimal.ZERO))
                .orElse(new Limits(fallbackManagerLimit, BigDecimal.ZERO));
    }

    public BigDecimal tillFloat(String hotelId) {
        return Money.of(reader.section(hotelId, SetupCatalogue.PAYMENTS).map(n -> n.path("tillFloat")).filter(n -> n.isNumber()).map(n -> n.decimalValue()).orElse(BigDecimal.ZERO));
    }

    public int lateMinutes(String hotelId) {
        return reader.section(hotelId, SetupCatalogue.STAFF).map(n -> n.path("lateMinutes").asInt(15)).orElse(15);
    }

    public boolean arrivalsResolvedFirst(String hotelId) {
        return reader.section(hotelId, SetupCatalogue.NIGHT_AUDIT).map(n -> n.path("arrivalsResolvedFirst").asBoolean(true)).orElse(true);
    }
}
