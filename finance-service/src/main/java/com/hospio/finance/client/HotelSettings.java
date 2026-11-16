package com.hospio.finance.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupSection;
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
        return reader.section(hotelId, SetupSection.ApprovalLimits.class).map(a -> new Limits(
                a.managerExpenseLimit() != null ? a.managerExpenseLimit() : fallbackManagerLimit,
                a.ownerExpenseLimit() != null ? a.ownerExpenseLimit() : BigDecimal.ZERO))
                .orElse(new Limits(fallbackManagerLimit, BigDecimal.ZERO));
    }

    public BigDecimal tillFloat(String hotelId) {
        return Money.of(reader.section(hotelId, SetupSection.Payments.class).map(SetupSection.Payments::tillFloat).orElse(BigDecimal.ZERO));
    }

    public int lateMinutes(String hotelId) {
        return reader.section(hotelId, SetupSection.Staff.class).map(SetupSection.Staff::lateMinutes).orElse(15);
    }

    /** Hotel setup → Staff & shifts: tell the managers about every clock-in, not only late ones (off by default). */
    public boolean notifyEveryClockIn(String hotelId) {
        return reader.section(hotelId, SetupSection.Staff.class).map(SetupSection.Staff::notifyEveryClockIn).orElse(false);
    }

    public boolean arrivalsResolvedFirst(String hotelId) {
        return reader.section(hotelId, SetupSection.NightAudit.class).map(SetupSection.NightAudit::arrivalsResolvedFirst).orElse(true);
    }
}
