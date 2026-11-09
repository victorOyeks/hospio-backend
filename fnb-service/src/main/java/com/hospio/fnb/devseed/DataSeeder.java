package com.hospio.fnb.devseed;

import static com.hospio.common.devseed.RestaurantBarSeed.*;

import com.hospio.common.devseed.RestaurantBarSeed.StockSeed;
import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.HappyHour;
import com.hospio.fnb.entity.MenuCategory;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuCategoryRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.rules.Recipes;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simpas Ikeja's Restaurant (a KITCHEN outlet, 8 tables, 12 dishes) and Bar (a BAR outlet, 4 tables, 12 drinks, a Monday to Friday
 * 17:00-19:00 happy hour at 20 %), linked to the Kitchen store and Bar store inventory-service seeds, with recipes that draw their stock.
 * Everything is created when missing; an outlet, item or price someone changed is left alone (an outlet with no store gets its store).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private record Dish(String category, String name, String description, int price, Object... recipe) {
        List<Recipes.Line> recipeLines() {
            List<Recipes.Line> out = new ArrayList<>();
            for (int i = 0; i < recipe.length; i += 2) {
                out.add(new Recipes.Line(((StockSeed) recipe[i]).id(), new BigDecimal(String.valueOf(recipe[i + 1]))));
            }
            return out;
        }
    }

    private static final List<String> RESTAURANT_CATEGORIES = List.of("Starters", "Mains", "Sides", "Light bites");
    private static final List<String> BAR_CATEGORIES = List.of("Beer", "Soft drinks", "Spirits", "Wine", "Cocktails");

    private static final List<Dish> DISHES = List.of(
            new Dish("Starters", "Catfish pepper soup", "Spicy broth with fresh catfish", 4500, CATFISH, "0.3", TOMATOES, "0.05"),
            new Dish("Starters", "Chicken wings", "Six wings, peppered and fried", 5000, CHICKEN, "0.3", VEGETABLE_OIL, "0.05"),
            new Dish("Mains", "Jollof rice and chicken", "Party jollof with grilled chicken", 6500, RICE, "0.2", CHICKEN, "0.25", TOMATOES, "0.1", VEGETABLE_OIL, "0.03"),
            new Dish("Mains", "Fried rice and beef", "Fried rice with peppered beef", 7000, RICE, "0.2", BEEF, "0.2", VEGETABLE_OIL, "0.03"),
            new Dish("Mains", "Pounded yam and egusi", "With assorted meat", 7500, YAM, "0.3", BEEF, "0.15", VEGETABLE_OIL, "0.04"),
            new Dish("Mains", "Grilled catfish", "Whole catfish, peppered tomato sauce", 9000, CATFISH, "0.5", TOMATOES, "0.1"),
            new Dish("Mains", "Beef steak and chips", "Grilled steak with chips", 12000, BEEF, "0.3", POTATOES, "0.3", VEGETABLE_OIL, "0.05"),
            new Dish("Sides", "Chips", "Golden fries", 3000, POTATOES, "0.3", VEGETABLE_OIL, "0.05"),
            new Dish("Sides", "Fried yam", "Yam chips with pepper sauce", 3000, YAM, "0.25", VEGETABLE_OIL, "0.05"),
            new Dish("Light bites", "Pancakes", "Three pancakes with syrup", 3500, FLOUR, "0.1", EGGS, "2"),
            new Dish("Light bites", "Omelette", "Three eggs, tomato and onion", 3500, EGGS, "3", VEGETABLE_OIL, "0.02", TOMATOES, "0.05"),
            new Dish("Light bites", "Club sandwich", "Chicken, egg and tomato, with chips", 6000, CHICKEN, "0.15", EGGS, "1", TOMATOES, "0.05"));

    private static final List<Dish> DRINKS = List.of(
            new Dish("Beer", "Star lager", "60cl bottle", 1500, STAR_LAGER, "1"),
            new Dish("Beer", "Heineken", "33cl bottle", 2000, HEINEKEN, "1"),
            new Dish("Soft drinks", "Coca-Cola", "50cl bottle", 800, COCA_COLA, "1"),
            new Dish("Soft drinks", "Bottled water", "75cl", 500, WATER, "1"),
            new Dish("Soft drinks", "Orange juice", "Glass, freshly poured", 1200, ORANGE_JUICE, "0.25"),
            new Dish("Spirits", "Vodka shot", "Single tot", 1500, VODKA, "1"),
            new Dish("Spirits", "Whisky shot", "Single tot", 2500, WHISKY, "1"),
            new Dish("Wine", "Red wine bottle", "75cl house red", 18000, RED_WINE, "1"),
            new Dish("Wine", "Red wine glass", "Glass of the house red", 3500, RED_WINE, "0.2"),
            new Dish("Cocktails", "Gin and tonic", "Gin, tonic and lime", 3000, GIN, "2", TONIC, "1"),
            new Dish("Cocktails", "Vodka orange", "Vodka with fresh orange juice", 2500, VODKA, "1", ORANGE_JUICE, "0.15"),
            new Dish("Cocktails", "Whisky and coke", "Whisky over ice with cola", 3000, WHISKY, "1", COCA_COLA, "1"));

    private final OutletRepository outlets;
    private final DiningTableRepository tables;
    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;
    private final HappyHourRepository happyHours;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        Tenant tenant = Tenant.systemFor(SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID, "seed", "Seed");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(status -> {
            int created = 0;
            created += outlet(RESTAURANT_OUTLET_ID, "Restaurant", Outlet.Kind.KITCHEN, KITCHEN_STORE_ID);
            created += outlet(BAR_OUTLET_ID, "Bar", Outlet.Kind.BAR, BAR_STORE_ID);
            created += tables(RESTAURANT_OUTLET_ID, "T", 8, new int[]{2, 2, 4, 4, 4, 6, 6, 8});
            created += tables(BAR_OUTLET_ID, "B", 4, new int[]{2, 2, 4, 4});
            created += categories(RESTAURANT_OUTLET_ID, RESTAURANT_CATEGORIES);
            created += categories(BAR_OUTLET_ID, BAR_CATEGORIES);
            created += menu(RESTAURANT_OUTLET_ID, DISHES);
            created += menu(BAR_OUTLET_ID, DRINKS);
            created += happyHour();
            if (created > 0) {
                log.info("Seeded {} restaurant and bar records (outlets, tables, categories, menu items, happy hour) for {}", created, SeedFixtures.HOTEL_NAME);
            } else {
                log.info("Restaurant and bar for {} already present; nothing seeded", SeedFixtures.HOTEL_NAME);
            }
        }));
    }

    private int outlet(String id, String name, Outlet.Kind kind, String storeId) {
        Outlet existing = outlets.findById(id).orElse(null);
        if (existing != null) {
            if (existing.getStoreId() == null) {
                existing.setStoreId(storeId);
                outlets.save(existing);
            }
            return 0;
        }
        Outlet o = Outlet.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(name).kind(kind).storeId(storeId).build();
        o.setId(id);
        outlets.save(o);
        return 1;
    }

    private int tables(String outletId, String prefix, int count, int[] seats) {
        int created = 0;
        var have = tables.findByOutletIdOrderByNumber(outletId).stream().map(DiningTable::getNumber).toList();
        for (int i = 1; i <= count; i++) {
            String number = prefix + i;
            if (!have.contains(number)) {
                tables.save(DiningTable.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).outletId(outletId).number(number)
                        .seats(seats[i - 1]).build());
                created++;
            }
        }
        return created;
    }

    private int categories(String outletId, List<String> names) {
        int created = 0;
        for (int i = 0; i < names.size(); i++) {
            if (categories.findByOutletIdAndName(outletId, names.get(i)).isEmpty()) {
                categories.save(MenuCategory.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).outletId(outletId)
                        .name(names.get(i)).sortOrder(i + 1).build());
                created++;
            }
        }
        return created;
    }

    private int menu(String outletId, List<Dish> dishes) {
        int created = 0;
        for (int i = 0; i < dishes.size(); i++) {
            Dish d = dishes.get(i);
            if (!items.existsByOutletIdAndName(outletId, d.name())) {
                items.save(MenuItem.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).outletId(outletId).category(d.category())
                        .name(d.name()).description(d.description()).price(new BigDecimal(d.price()).setScale(2)).sortOrder(i + 1)
                        .recipeJson(Recipes.write(d.recipeLines())).build());
                created++;
            }
        }
        return created;
    }

    private int happyHour() {
        if (!happyHours.findByOutletIdAndActiveTrue(BAR_OUTLET_ID).isEmpty()) {
            return 0;
        }
        happyHours.save(HappyHour.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).outletId(BAR_OUTLET_ID)
                .name("Happy hour").fromTime(LocalTime.of(17, 0)).toTime(LocalTime.of(19, 0)).days("MON,TUE,WED,THU,FRI")
                .discountPct(new BigDecimal("20")).build());
        return 1;
    }
}
