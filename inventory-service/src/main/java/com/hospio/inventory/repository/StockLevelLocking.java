package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockLevel;
import java.util.Collection;

/**
 * Row locks for stock levels, so two writers can't both read the same quantity and save over each other
 * (a lost decrement, or a draw past zero). Every stock change goes through {@link #lockLevel}.
 */
public interface StockLevelLocking {

    /**
     * The level of an item in a store, created at zero if it has none yet (insert … on conflict do nothing, so two first uses at once
     * don't trip the unique (item_id, store_id)), locked FOR UPDATE until the transaction ends and re-read from the database.
     */
    StockLevel lockLevel(String businessId, String hotelId, String itemId, String storeId);

    /**
     * Locks the existing levels of these items in these stores in one statement, in id order. Paths that move several lines lock
     * them all first, so two of them touching the same rows in a different order wait for each other instead of deadlocking.
     */
    void lockLevels(Collection<String> itemIds, Collection<String> storeIds);
}
