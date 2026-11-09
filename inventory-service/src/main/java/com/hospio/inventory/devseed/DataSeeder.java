package com.hospio.inventory.devseed;

import com.hospio.common.devseed.RestaurantBarSeed;
import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.entity.StockMovement;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.repository.StockItemRepository;
import com.hospio.inventory.repository.StockLevelRepository;
import com.hospio.inventory.repository.StockMovementRepository;
import com.hospio.inventory.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Kitchen store and Bar store for Simpas Ikeja with the stock the seeded recipes use, at opening levels, so a sale in the
 * restaurant or bar visibly draws stock. Created when missing (a store, item or level someone changed is left alone); the ids come
 * from {@link RestaurantBarSeed} so fnb-service's outlets and recipes line up.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final StoreRepository stores;
    private final StockItemRepository items;
    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        Tenant tenant = Tenant.systemFor(SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID, "seed", "Seed");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(status -> {
            int created = 0;
            created += store(RestaurantBarSeed.KITCHEN_STORE_ID, "Kitchen store", "KITCHEN");
            created += store(RestaurantBarSeed.BAR_STORE_ID, "Bar store", "BAR");
            for (RestaurantBarSeed.StockSeed s : RestaurantBarSeed.STOCK) {
                if (items.findById(s.id()).isEmpty()) {
                    StockItem item = StockItem.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(s.name())
                            .category(s.category()).unit(s.unit()).minLevel(s.minLevel()).unitCost(s.unitCost()).build();
                    item.setId(s.id());
                    items.save(item);
                    created++;
                    if (levels.findByItemIdAndStoreId(s.id(), s.storeId()).isEmpty()) {
                        levels.save(StockLevel.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).itemId(s.id())
                                .storeId(s.storeId()).quantity(s.opening()).build());
                        movements.save(StockMovement.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).itemId(s.id())
                                .storeId(s.storeId()).kind(StockMovement.Kind.RECEIVED).quantity(s.opening()).reference("OPENING")
                                .note("Opening stock").byUser("seed").build());
                    }
                }
            }
            if (created > 0) {
                log.info("Seeded {} kitchen and bar stores and stock items for {}", created, SeedFixtures.HOTEL_NAME);
            } else {
                log.info("Kitchen and bar stock for {} already present; nothing seeded", SeedFixtures.HOTEL_NAME);
            }
        }));
    }

    private int store(String id, String name, String kind) {
        if (stores.findById(id).isPresent()) {
            return 0;
        }
        Store s = Store.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(name).kind(kind).build();
        s.setId(id);
        stores.save(s);
        return 1;
    }
}
