package com.hospio.common.devseed;

import java.math.BigDecimal;
import java.util.List;

/**
 * Simpas Ikeja's restaurant and bar: the outlets (fnb-service), the stores that feed them and the stock items the seeded recipes
 * use (inventory-service). Fixed ids so the two services agree without asking each other, as {@link SeedFixtures} does.
 */
public final class RestaurantBarSeed {

    private RestaurantBarSeed() {
    }

    public static final String RESTAURANT_OUTLET_ID = "70000000-0000-4000-8000-000000000001";
    public static final String BAR_OUTLET_ID = "70000000-0000-4000-8000-000000000002";
    public static final String KITCHEN_STORE_ID = "80000000-0000-4000-8000-000000000001";
    public static final String BAR_STORE_ID = "80000000-0000-4000-8000-000000000002";

    public record StockSeed(String id, String name, String category, String unit, String storeId, BigDecimal minLevel, BigDecimal opening,
                            BigDecimal unitCost) {
    }

    private static String id(int n) {
        return String.format("90000000-0000-4000-8000-%012d", n);
    }

    private static StockSeed s(int n, String name, String category, String unit, String store, int min, int opening, int cost) {
        return new StockSeed(id(n), name, category, unit, store, BigDecimal.valueOf(min), BigDecimal.valueOf(opening), BigDecimal.valueOf(cost));
    }

    // Kitchen store
    public static final StockSeed RICE = s(1, "Rice", "Dry goods", "kg", KITCHEN_STORE_ID, 5, 40, 1800);
    public static final StockSeed CHICKEN = s(2, "Chicken", "Meat", "kg", KITCHEN_STORE_ID, 4, 25, 4500);
    public static final StockSeed BEEF = s(3, "Beef", "Meat", "kg", KITCHEN_STORE_ID, 3, 20, 6500);
    public static final StockSeed CATFISH = s(4, "Catfish", "Fish", "kg", KITCHEN_STORE_ID, 2, 12, 5500);
    public static final StockSeed TOMATOES = s(5, "Tomatoes", "Produce", "kg", KITCHEN_STORE_ID, 3, 15, 1500);
    public static final StockSeed VEGETABLE_OIL = s(6, "Vegetable oil", "Dry goods", "litres", KITCHEN_STORE_ID, 4, 25, 2800);
    public static final StockSeed YAM = s(7, "Yam", "Produce", "kg", KITCHEN_STORE_ID, 5, 30, 1200);
    public static final StockSeed EGGS = s(8, "Eggs", "Dairy", "pcs", KITCHEN_STORE_ID, 12, 90, 120);
    public static final StockSeed FLOUR = s(9, "Flour", "Dry goods", "kg", KITCHEN_STORE_ID, 3, 15, 1300);
    public static final StockSeed POTATOES = s(10, "Potatoes", "Produce", "kg", KITCHEN_STORE_ID, 5, 25, 1400);

    // Bar store
    public static final StockSeed STAR_LAGER = s(11, "Star lager", "Beer", "bottles", BAR_STORE_ID, 24, 120, 900);
    public static final StockSeed HEINEKEN = s(12, "Heineken", "Beer", "bottles", BAR_STORE_ID, 24, 96, 1200);
    public static final StockSeed COCA_COLA = s(13, "Coca-Cola", "Soft drinks", "bottles", BAR_STORE_ID, 24, 96, 350);
    public static final StockSeed WATER = s(14, "Bottled water", "Soft drinks", "bottles", BAR_STORE_ID, 24, 120, 200);
    public static final StockSeed ORANGE_JUICE = s(15, "Orange juice", "Soft drinks", "litres", BAR_STORE_ID, 4, 20, 1800);
    public static final StockSeed VODKA = s(16, "Vodka", "Spirits", "tots", BAR_STORE_ID, 20, 100, 500);
    public static final StockSeed WHISKY = s(17, "Whisky", "Spirits", "tots", BAR_STORE_ID, 20, 100, 900);
    public static final StockSeed GIN = s(18, "Gin", "Spirits", "tots", BAR_STORE_ID, 20, 80, 600);
    public static final StockSeed TONIC = s(19, "Tonic water", "Soft drinks", "bottles", BAR_STORE_ID, 12, 48, 300);
    public static final StockSeed RED_WINE = s(20, "Red wine", "Wine", "bottles", BAR_STORE_ID, 6, 24, 9000);

    public static final List<StockSeed> STOCK = List.of(RICE, CHICKEN, BEEF, CATFISH, TOMATOES, VEGETABLE_OIL, YAM, EGGS, FLOUR, POTATOES,
            STAR_LAGER, HEINEKEN, COCA_COLA, WATER, ORANGE_JUICE, VODKA, WHISKY, GIN, TONIC, RED_WINE);
}
