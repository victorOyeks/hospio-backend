package com.hospio.inventory.devseed;

import com.hospio.common.devseed.RestaurantBarSeed;
import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.entity.StockMovement;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.entity.Supplier;
import com.hospio.inventory.repository.StockItemRepository;
import com.hospio.inventory.repository.StockLevelRepository;
import com.hospio.inventory.repository.StockMovementRepository;
import com.hospio.inventory.repository.StoreRepository;
import com.hospio.inventory.repository.SupplierRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
    private final SupplierRepository suppliers;
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
            created += mainStoreAndSuppliers();
            if (created > 0) {
                log.info("Seeded {} kitchen and bar stores and stock items for {}", created, SeedFixtures.HOTEL_NAME);
            } else {
                log.info("Kitchen and bar stock for {} already present; nothing seeded", SeedFixtures.HOTEL_NAME);
            }
        }));
    }

    static final String MAIN_STORE_ID = "80000000-0000-4000-8000-000000000003";
    static final String FRESH_FOODS = "81000000-0000-4000-8000-000000000001";
    static final String DRINKS_DEPOT = "81000000-0000-4000-8000-000000000002";
    static final String CLEANPRO = "81000000-0000-4000-8000-000000000003";
    private static final Set<String> FOOD = Set.of("Dry goods", "Meat", "Fish", "Produce", "Dairy");
    private static final Set<String> DRINKS = Set.of("Beer", "Soft drinks", "Spirits", "Wine");

    private record Extra(int n, String name, String unit, int min, int opening, int cost) {
        String id() {
            return String.format("90000000-0000-4000-8000-%012d", n);
        }
    }

    private static final List<Extra> HOUSEKEEPING = List.of(new Extra(21, "Bath soap", "pcs", 50, 200, 250),
            new Extra(22, "Toilet roll", "pcs", 60, 240, 300), new Extra(23, "Hand towel", "pcs", 20, 80, 2500),
            new Extra(24, "Detergent", "kg", 10, 40, 1500), new Extra(25, "Bin liners", "pcs", 50, 200, 80));

    /** The main store with back stock of every kitchen and bar item plus housekeeping supplies, and three suppliers linked to them. */
    private int mainStoreAndSuppliers() {
        int created = store(MAIN_STORE_ID, "Main store", "MAIN");
        created += supplier(FRESH_FOODS, "Lagos Fresh Foods", "Bisi Adeyemi", "0803 555 0101", "orders@lagosfreshfoods.ng", "7 days");
        created += supplier(DRINKS_DEPOT, "Ikeja Drinks Depot", "Emeka Obi", "0805 555 0202", "sales@ikejadrinks.ng", "Pay on delivery");
        created += supplier(CLEANPRO, "CleanPro Supplies", "Funke Bello", "0807 555 0303", null, "14 days");
        for (Extra x : HOUSEKEEPING) {
            if (items.findById(x.id()).isEmpty()) {
                StockItem item = StockItem.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(x.name()).category("Housekeeping")
                        .unit(x.unit()).minLevel(BigDecimal.valueOf(x.min())).parLevel(BigDecimal.valueOf(x.min() * 4L)).unitCost(BigDecimal.valueOf(x.cost()))
                        .supplierId(CLEANPRO).build();
                item.setId(x.id());
                items.save(item);
                created++;
            }
            created += opening(x.id(), BigDecimal.valueOf(x.opening()));
        }
        for (RestaurantBarSeed.StockSeed s : RestaurantBarSeed.STOCK) {
            items.findById(s.id()).ifPresent(i -> {
                boolean changed = false;
                if (i.getSupplierId() == null) {
                    i.setSupplierId(FOOD.contains(s.category()) ? FRESH_FOODS : DRINKS.contains(s.category()) ? DRINKS_DEPOT : null);
                    changed = i.getSupplierId() != null;
                }
                if (i.getParLevel() == null || i.getParLevel().signum() == 0) {
                    i.setParLevel(s.minLevel().multiply(BigDecimal.valueOf(4)));
                    changed = true;
                }
                if (changed) {
                    items.save(i);
                }
            });
            created += opening(s.id(), s.opening().multiply(BigDecimal.valueOf(2)));
        }
        return created;
    }

    private int opening(String itemId, BigDecimal quantity) {
        if (items.findById(itemId).isEmpty() || levels.findByItemIdAndStoreId(itemId, MAIN_STORE_ID).isPresent()) {
            return 0;
        }
        levels.save(StockLevel.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).itemId(itemId).storeId(MAIN_STORE_ID)
                .quantity(quantity).build());
        BigDecimal cost = items.findById(itemId).map(StockItem::getUnitCost).orElse(null);
        movements.save(StockMovement.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).itemId(itemId).storeId(MAIN_STORE_ID)
                .kind(StockMovement.Kind.RECEIVED).quantity(quantity).unitCost(cost).reference("OPENING").note("Opening stock").byUser("seed").byName("Seed").build());
        return 1;
    }

    private int supplier(String id, String name, String contact, String phone, String email, String terms) {
        if (suppliers.findById(id).isPresent()) {
            return 0;
        }
        Supplier s = Supplier.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(name).contactName(contact).phone(phone)
                .email(email).paymentTerms(terms).build();
        s.setId(id);
        suppliers.save(s);
        return 1;
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
