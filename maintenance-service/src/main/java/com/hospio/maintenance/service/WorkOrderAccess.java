package com.hospio.maintenance.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.maintenance.entity.Repair;
import java.util.List;

/** Who may do what to a work order (docs/laundry-maintenance.md · Roles). Route guards come first; these re-check per record. */
public final class WorkOrderAccess {

    private WorkOrderAccess() {
    }

    /** The roles that raise work orders, in the order the raiser's role is named. */
    static final List<Role> RAISERS = List.of(Role.MANAGER, Role.OWNER, Role.MAINTENANCE, Role.FRONT_OFFICE, Role.HOUSEKEEPING);

    public static boolean manager(HospioUserDetails u) {
        return !u.isReadOnly() && (u.isPlatform() || u.isManagerOrOwner());
    }

    /** The maintenance supervisor (Full) or a manager. */
    public static boolean supervisor(HospioUserDetails u) {
        return manager(u) || (!u.isReadOnly() && u.has(Role.MAINTENANCE, AccessLevel.FULL));
    }

    public static boolean technician(HospioUserDetails u) {
        return !u.isReadOnly() && u.has(Role.MAINTENANCE, AccessLevel.EDIT);
    }

    /** Start, hold, resume, complete, take out of order: the technician on it, the supervisor or a manager. */
    public static boolean canRun(HospioUserDetails u, Repair r) {
        return supervisor(u) || (technician(u) && u.getUserId().equals(r.getAssignedTo()));
    }

    /** A technician may take an unassigned work order (or one already theirs); giving it to someone else is the supervisor's. */
    public static boolean canAssign(HospioUserDetails u, Repair r, String to) {
        if (supervisor(u)) {
            return true;
        }
        return technician(u) && u.getUserId().equals(to) && (r.getAssignedTo() == null || u.getUserId().equals(r.getAssignedTo()));
    }

    /** Whoever raised it before work starts; the supervisor or a manager at any open stage. */
    public static boolean canCancel(HospioUserDetails u, Repair r) {
        if (supervisor(u)) {
            return true;
        }
        return !u.isReadOnly() && u.getUserId().equals(r.getReportedBy())
                && (r.getStatus() == Repair.Status.OPEN || r.getStatus() == Repair.Status.ASSIGNED);
    }

    public static boolean canReopen(HospioUserDetails u, Repair r) {
        return manager(u) || (!u.isReadOnly() && u.getUserId().equals(r.getReportedBy()));
    }

    /** Out of order / back in service: maintenance (Edit or Full) or a manager. */
    public static boolean canTakeOutOfOrder(HospioUserDetails u) {
        return manager(u) || technician(u);
    }

    /** The role a work order was raised as, e.g. FRONT_OFFICE; SYSTEM for the platform. */
    public static String raiserRole(HospioUserDetails u) {
        return RAISERS.stream().filter(u::has).map(Role::name).findFirst().orElse(u.isPlatform() ? "SYSTEM" : null);
    }
}
