package com.hospio.fnb.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.StaffOutlet;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.StaffOutletRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outlets a person works (docs/plan-venue-type.md, phase 4). Optional: with no list a waiter, cashier, bar or kitchen person
 * works every outlet of the location their role allows, as before. With a list, only those: orders, tabs, sales and payments at
 * any other outlet are refused. Managers, owners, finance and Hospio360 are never narrowed.
 */
@Service
@RequiredArgsConstructor
public class StaffOutlets {

    private final StaffOutletRepository rows;
    private final OutletRepository outlets;

    /** {@code restricted} false: every outlet (then {@code outletIds} is empty). */
    public record Assignment(String userId, boolean restricted, List<String> outletIds) {
    }

    public static boolean neverNarrowed(HospioUserDetails u) {
        return u.isPlatform() || u.isManagerOrOwner() || u.has(Role.FINANCE);
    }

    /** The outlet ids this person is kept to at the hotel; empty when they are not narrowed. */
    @Transactional(readOnly = true)
    public Set<String> listed(String hotelId, String userId) {
        if (userId == null || hotelId == null) {
            return Set.of();
        }
        return rows.findByHotelIdAndUserId(hotelId, userId).stream().map(StaffOutlet::getOutletId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional(readOnly = true)
    public boolean allowed(HospioUserDetails user, Outlet outlet) {
        if (neverNarrowed(user)) {
            return true;
        }
        Set<String> mine = listed(outlet.getHotelId(), user.getUserId());
        return mine.isEmpty() || mine.contains(outlet.getId());
    }

    /** As {@link #allowed} for a person known only by id (payment-service asking before it takes money). */
    @Transactional(readOnly = true)
    public boolean allowed(String userId, Outlet outlet) {
        Set<String> mine = listed(outlet.getHotelId(), userId);
        return mine.isEmpty() || mine.contains(outlet.getId());
    }

    public void require(HospioUserDetails user, Outlet outlet) {
        if (!allowed(user, outlet)) {
            throw new ForbiddenException(refusal(outlet));
        }
    }

    public static String refusal(Outlet outlet) {
        return "You aren't assigned to " + outlet.getName() + ". A manager can add it to your outlets (Staff → Edit)";
    }

    @Transactional(readOnly = true)
    public Assignment mine() {
        HospioUserDetails user = CurrentUser.require();
        if (neverNarrowed(user)) {
            return new Assignment(user.getUserId(), false, List.of());
        }
        return of(TenantContext.requireHotel(), user.getUserId());
    }

    @Transactional(readOnly = true)
    public Assignment of(String hotelId, String userId) {
        List<String> ids = List.copyOf(listed(hotelId, userId));
        return new Assignment(userId, !ids.isEmpty(), ids);
    }

    /** Everyone at the hotel who is kept to a list, by user id. */
    @Transactional(readOnly = true)
    public Map<String, List<String>> all(String hotelId) {
        return rows.findByHotelId(hotelId).stream().collect(Collectors.groupingBy(StaffOutlet::getUserId,
                Collectors.mapping(StaffOutlet::getOutletId, Collectors.toList())));
    }

    /** Replace the person's list; an empty list (or every outlet of the location) means every outlet again. */
    @Transactional
    public Assignment set(String userId, List<String> outletIds) {
        String hotelId = TenantContext.requireHotel();
        List<Outlet> here = outlets.findByHotelIdOrderByName(hotelId);
        Set<String> known = here.stream().map(Outlet::getId).collect(Collectors.toSet());
        Set<String> wanted = new LinkedHashSet<>(outletIds == null ? List.of() : outletIds);
        for (String id : wanted) {
            if (!known.contains(id)) {
                throw new BadRequestException("That outlet isn't at this location");
            }
        }
        if (wanted.size() == known.size()) {
            wanted.clear();
        }
        List<StaffOutlet> existing = rows.findByHotelIdAndUserId(hotelId, userId);
        rows.deleteAll(existing.stream().filter(r -> !wanted.contains(r.getOutletId())).toList());
        Set<String> kept = existing.stream().map(StaffOutlet::getOutletId).collect(Collectors.toSet());
        for (String id : wanted) {
            if (!kept.contains(id)) {
                rows.save(StaffOutlet.builder().userId(userId).outletId(id).build());
            }
        }
        rows.flush();
        return new Assignment(userId, !wanted.isEmpty(), List.copyOf(wanted));
    }
}
