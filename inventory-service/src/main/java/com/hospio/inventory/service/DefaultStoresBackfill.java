package com.hospio.inventory.service;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.client.TenantHotels;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gives every existing hotel with no store at all the default Main, Kitchen, Bar and Housekeeping stores, through the same
 * {@link InventoryService#createDefaultStores} the HOTEL_CREATED listener uses (docs/inventory-stores.md). Each hotel is done in its
 * own transaction as that hotel's system tenant, so the row-level policies apply; a hotel with any store is left alone, so a second
 * run changes nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultStoresBackfill {

    private final TenantHotels hotels;
    private final InventoryService service;

    public record Result(int hotelsChecked, int hotelsChanged, int storesCreated, List<String> failed) {
    }

    /** Not transactional: each hotel commits on its own, under its own tenant. */
    public Result run() {
        int checked = 0;
        int changed = 0;
        int created = 0;
        List<String> failed = new ArrayList<>();
        for (TenantHotels.HotelRef h : hotels.all()) {
            if (h.id() == null || h.businessId() == null) {
                continue;
            }
            checked++;
            try {
                int n = TenantContext.runWith(Tenant.systemFor(h.businessId(), h.id(), "system", "system:default-stores-backfill"),
                        () -> service.createDefaultStores(h.id()));
                if (n > 0) {
                    changed++;
                    created += n;
                    log.info("Default stores backfill: hotel {} ({}) got {} stores", h.id(), h.name(), n);
                }
            } catch (RuntimeException e) {
                failed.add(h.id());
                log.error("Default stores backfill: hotel {} ({}) failed: {}", h.id(), h.name(), e.getMessage(), e);
            }
        }
        log.info("Default stores backfill: {} hotels checked, {} changed, {} stores created, {} failed", checked, changed, created, failed.size());
        return new Result(checked, changed, created, failed);
    }
}
