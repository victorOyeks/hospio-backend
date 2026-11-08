package com.hospio.common.enums;

/**
 * Roles come from the modules; hotels give them to people at three levels (see {@link AccessLevel}).
 * OWNER and MANAGER are hotel-wide. SUPER_ADMIN, SUPPORT and FINANCE_ADMIN are H.M.S staff; MARKETER
 * uses the marketer portal.
 */
public enum Role {
    OWNER, MANAGER,
    FRONT_OFFICE, HOUSEKEEPING, LAUNDRY, MAINTENANCE, KITCHEN, BAR, INVENTORY, FINANCE,
    SUPER_ADMIN, SUPPORT, FINANCE_ADMIN, MARKETER, SYSTEM;

    public boolean isPlatform() {
        return this == SUPER_ADMIN || this == SUPPORT || this == FINANCE_ADMIN || this == SYSTEM;
    }
}
