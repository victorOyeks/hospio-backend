package com.hospio.common.tenancy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.UnauthorizedException;
import java.util.Optional;
import java.util.function.Supplier;

/** Thread-bound tenant. Set by JwtFilter, by Kafka listeners and by jobs; read by entities and the datasource. */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Tenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Tenant require() {
        return current().orElseThrow(() -> new UnauthorizedException("No tenant in context"));
    }

    public static String requireBusiness() {
        Tenant t = require();
        if (t.businessId() == null) {
            throw new BadRequestException("This action needs a hotel business in context");
        }
        return t.businessId();
    }

    public static String requireHotel() {
        Tenant t = require();
        if (t.hotelId() == null) {
            throw new BadRequestException("Choose a hotel first (X-Hotel-Id header)");
        }
        return t.hotelId();
    }

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code work} as {@code tenant}, then restores the previous tenant. Changes made inside are
     * flushed to the database before the switch back, so they are written under this tenant's row-level
     * scope rather than at the caller's commit (see {@link TenantFlushSupport}).
     */
    public static <T> T runWith(Tenant tenant, Supplier<T> work) {
        Tenant previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            T result = work.get();
            if (previous != null && !previous.equals(tenant)) {
                TenantFlushSupport.flushIfActive();
            }
            return result;
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWith(Tenant tenant, Runnable work) {
        runWith(tenant, () -> {
            work.run();
            return null;
        });
    }
}
