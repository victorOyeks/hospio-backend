package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

class StockLevelLockingImpl implements StockLevelLocking {

    @PersistenceContext
    private EntityManager em;

    @Override
    public StockLevel lockLevel(String businessId, String hotelId, String itemId, String storeId) {
        // Pending changes first: the refresh below must not throw away a level moved earlier in this transaction.
        em.flush();
        Instant now = Instant.now();
        em.createNativeQuery("INSERT INTO inventory.stock_levels (id, business_id, hotel_id, item_id, store_id, quantity, created_at, updated_at) "
                        + "VALUES (:id, :businessId, :hotelId, :itemId, :storeId, 0, :now, :now) ON CONFLICT (item_id, store_id) DO NOTHING")
                .setParameter("id", UUID.randomUUID().toString()).setParameter("businessId", businessId).setParameter("hotelId", hotelId)
                .setParameter("itemId", itemId).setParameter("storeId", storeId).setParameter("now", now).executeUpdate();
        StockLevel level = em.createQuery("select l from StockLevel l where l.itemId = :itemId and l.storeId = :storeId", StockLevel.class)
                .setParameter("itemId", itemId).setParameter("storeId", storeId).getSingleResult();
        // SELECT … FOR UPDATE and overwrite whatever an earlier read left in the persistence context with the committed quantity.
        em.refresh(level, LockModeType.PESSIMISTIC_WRITE);
        return level;
    }

    @Override
    public void lockLevels(Collection<String> itemIds, Collection<String> storeIds) {
        if (itemIds == null || itemIds.isEmpty() || storeIds == null || storeIds.isEmpty()) {
            return;
        }
        em.flush();
        em.createNativeQuery("SELECT id FROM inventory.stock_levels WHERE item_id IN (:itemIds) AND store_id IN (:storeIds) ORDER BY id FOR UPDATE")
                .setParameter("itemIds", itemIds).setParameter("storeIds", storeIds).getResultList();
    }
}
