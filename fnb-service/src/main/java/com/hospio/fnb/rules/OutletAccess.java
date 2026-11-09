package com.hospio.fnb.rules;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.fnb.entity.Outlet;

/**
 * Who works which outlet: KITCHEN the kitchen and restaurant outlets, BAR the bar outlets. Managers, owners and the platform work
 * all of them; a read-only token (every role VIEW, or support access) works none and is never "full".
 */
public final class OutletAccess {

    private OutletAccess() {
    }

    public static Role roleFor(Outlet outlet) {
        return outlet.getKind() == Outlet.Kind.BAR ? Role.BAR : Role.KITCHEN;
    }

    public static boolean manager(HospioUserDetails u) {
        return !u.isReadOnly() && (u.isPlatform() || u.isManagerOrOwner());
    }

    /** May take orders and move them along at this outlet (Edit on the outlet's role, or a manager). */
    public static boolean works(HospioUserDetails u, Outlet outlet) {
        return manager(u) || (!u.isReadOnly() && u.has(roleFor(outlet), AccessLevel.EDIT));
    }

    /** May manage this outlet's menu, happy hours and categories, cancel sent lines, see inactive items (Full on the role, or a manager). */
    public static boolean full(HospioUserDetails u, Outlet outlet) {
        return manager(u) || (!u.isReadOnly() && u.has(roleFor(outlet), AccessLevel.FULL));
    }

    /** May look at the outlet's orders and sales: anyone who works it, or any manager view, or finance. */
    public static boolean sees(HospioUserDetails u, Outlet outlet) {
        return u.isPlatform() || u.isManagerOrOwner() || u.has(roleFor(outlet)) || u.has(Role.FINANCE);
    }

    /** Front office takes room-service orders to the kitchen or bar and charges rooms. */
    public static boolean frontOffice(HospioUserDetails u) {
        return !u.isReadOnly() && u.has(Role.FRONT_OFFICE, AccessLevel.EDIT);
    }

    /** Someone outside the outlet's own team: its role is told when they open an order. */
    public static boolean outside(HospioUserDetails u, Outlet outlet) {
        return !u.isPlatform() && !u.isManagerOrOwner() && !u.has(roleFor(outlet));
    }
}
