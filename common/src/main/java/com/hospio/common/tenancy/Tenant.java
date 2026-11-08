package com.hospio.common.tenancy;

import com.hospio.common.enums.Role;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import java.util.Set;

/**
 * Who is acting and on whose data. {@code scope} is {@code hotel} for staff and managers bound to one
 * hotel, {@code business} for an owner looking across hotels, and {@code platform} for H.M.S staff and
 * background work. The Postgres policies read exactly these three values.
 */
public record Tenant(String businessId, String hotelId, String actorId, String actorName, Set<Role> roles, String scope) {

    public static Tenant fromUser(HospioUserDetails user, String hotelHeader) {
        if (user.isPlatform()) {
            return new Tenant(null, hotelHeader, user.getUserId(), user.getFullName(), user.roleSet(), JwtUtil.SCOPE_PLATFORM);
        }
        if (user.isMarketer()) {
            // No business and not the platform: app.can_see answers false for every tenant row, which is right for now.
            return new Tenant(null, hotelHeader, user.getUserId(), user.getFullName(), user.roleSet(), JwtUtil.SCOPE_MARKETER);
        }
        String hotel = hotelHeader;
        if (hotel == null && user.getHotelIds().size() == 1 && !user.has(Role.OWNER)) {
            hotel = user.getHotelIds().get(0);
        }
        // Support access: the activity log shows who at H.M.S was looking, not the owner.
        String actorName = user.getActedBy() == null ? user.getFullName() : "H.M.S \u00b7 " + user.getFullName();
        return new Tenant(user.getBusinessId(), hotel, user.getUserId(), actorName, user.roleSet(),
                hotel == null ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
    }

    public static Tenant system(String jobName) {
        return new Tenant(null, null, "system", "system:" + jobName, Set.of(Role.SYSTEM), JwtUtil.SCOPE_PLATFORM);
    }

    /**
     * A system actor working inside one business and hotel, e.g. a Kafka listener applying an event. An
     * event with no business (a platform user signing in, a platform job) binds as the platform: a
     * business scope with no business id would see and write nothing, so the row-level policy would
     * refuse the activity-log insert and the listener would fail the record.
     */
    public static Tenant systemFor(String businessId, String hotelId, String actorId, String actorName) {
        String scope = businessId == null ? JwtUtil.SCOPE_PLATFORM : hotelId == null ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL;
        return new Tenant(businessId, hotelId, actorId == null ? "system" : actorId, actorName == null ? "system" : actorName,
                Set.of(Role.SYSTEM), scope);
    }

    public boolean isPlatform() {
        return JwtUtil.SCOPE_PLATFORM.equals(scope);
    }

    public Tenant forHotel(String hotel) {
        return new Tenant(businessId, hotel, actorId, actorName, roles, isPlatform() ? scope : JwtUtil.SCOPE_HOTEL);
    }
}
