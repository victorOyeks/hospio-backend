package com.hospio.fnb.rules;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.fnb.entity.Outlet;

/**
 * Who works which outlet: KITCHEN the kitchen and restaurant outlets, BAR the bar outlets. Managers, owners and the platform work
 * all of them; a read-only token (every role VIEW, or support access) works none and is never "full".
 *
 * <p>Floor roles (docs/plan-venue-type.md, phase 3), at every outlet of the location: a WAITER takes orders, sends them and serves,
 * on the orders they opened (Full access: a head waiter, on anyone's); a CASHIER sees every order and takes the money, and never
 * rings anything up. Neither makes a dish ready, cancels what was sent or touches the menu.
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

    /** May look at the outlet's orders and sales: anyone who works it, a waiter or cashier, or any manager view, or finance. */
    public static boolean sees(HospioUserDetails u, Outlet outlet) {
        return u.isPlatform() || u.isManagerOrOwner() || u.has(roleFor(outlet)) || u.has(Role.FINANCE) || u.has(Role.CASHIER) || u.has(Role.WAITER);
    }

    public static boolean waiter(HospioUserDetails u) {
        return !u.isReadOnly() && u.has(Role.WAITER, AccessLevel.EDIT);
    }

    public static boolean cashier(HospioUserDetails u) {
        return !u.isReadOnly() && u.has(Role.CASHIER, AccessLevel.EDIT);
    }

    /** May take orders at this outlet: whoever works it, or a waiter. */
    public static boolean takes(HospioUserDetails u, Outlet outlet) {
        return works(u, outlet) || waiter(u);
    }

    /**
     * Sees and works only the orders they opened: a waiter below Full access who holds nothing else that shows the outlet's orders
     * (its own role, cashier, finance, management).
     */
    public static boolean ownOnly(HospioUserDetails u, Outlet outlet) {
        return u.has(Role.WAITER) && !u.has(Role.WAITER, AccessLevel.FULL) && !u.isPlatform() && !u.isManagerOrOwner()
                && !u.has(roleFor(outlet)) && !u.has(Role.FINANCE) && !u.has(Role.CASHIER);
    }

    public static boolean own(HospioUserDetails u, String openedBy) {
        return u.getUserId() != null && u.getUserId().equals(openedBy);
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
