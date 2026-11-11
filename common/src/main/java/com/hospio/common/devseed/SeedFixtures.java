package com.hospio.common.devseed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The test accounts and the example hotel from the design handoff. Every service that seeds uses the
 * same fixed ids so they agree without asking each other: Royal Suites Ltd, Simpas Ikeja (120 rooms),
 * owner Godwin Kwin, GM Tunde Bakare, the named staff and the H.M.S admins.
 *
 * Seeding runs when {@code hospio.seed.enabled=true} (env {@code SEED_DEMO_DATA}), on every start,
 * and only creates what is missing; it never overwrites a row QA has changed.
 */
public final class SeedFixtures {

    private SeedFixtures() {
    }

    public static final String SEED_PASSWORD = "Hospio123!";
    public static final String SEED_PIN = "258013";

    public static final String BUSINESS_ID = "b0000000-0000-4000-8000-000000000001";
    public static final String BUSINESS_NAME = "Royal Suites Ltd";
    public static final String HOTEL_ID = "a0000000-0000-4000-8000-000000000001";
    public static final String HOTEL_NAME = "Simpas Ikeja";

    public record Person(String userId, String fullName, String email, String phone, String role) {
    }

    public static final Person OWNER = new Person("10000000-0000-4000-8000-000000000001", "Godwin Kwin", "godwin@royalsuites.ng", "+2348010000001", "OWNER");
    public static final Person MANAGER = new Person("10000000-0000-4000-8000-000000000002", "Tunde Bakare", "tunde@royalsuites.ng", "+2348010000002", "MANAGER");

    public static final List<Person> STAFF = List.of(
            new Person("10000000-0000-4000-8000-000000000011", "Victor Adeyemi", "victor@royalsuites.ng", "+2348010000011", "FRONT_OFFICE"),
            new Person("10000000-0000-4000-8000-000000000012", "Grace Okafor", "grace@royalsuites.ng", "+2348010000012", "HOUSEKEEPING"),
            new Person("10000000-0000-4000-8000-000000000013", "Kemi Adebayo", "kemi@royalsuites.ng", "+2348010000013", "HOUSEKEEPING"),
            new Person("10000000-0000-4000-8000-000000000014", "Lola Eze", "lola@royalsuites.ng", "+2348010000014", "LAUNDRY"),
            new Person("10000000-0000-4000-8000-000000000015", "Chinedu Obi", "chinedu@royalsuites.ng", "+2348010000015", "MAINTENANCE"),
            new Person("10000000-0000-4000-8000-000000000016", "Mariam Bello", "mariam@royalsuites.ng", "+2348010000016", "KITCHEN"),
            new Person("10000000-0000-4000-8000-000000000017", "Segun Ajayi", "segun@royalsuites.ng", "+2348010000017", "BAR"),
            new Person("10000000-0000-4000-8000-000000000018", "Jide Alabi", "jide@royalsuites.ng", "+2348010000018", "INVENTORY"),
            new Person("10000000-0000-4000-8000-000000000019", "Femi Ojo", "femi@royalsuites.ng", "+2348010000019", "FINANCE"),
            new Person("10000000-0000-4000-8000-000000000020", "Toyin Balogun", "toyin@royalsuites.ng", "+2348010000020", "FRONT_OFFICE"));

    public static final List<Person> ADMINS = List.of(
            new Person("20000000-0000-4000-8000-000000000001", "Amoria Nwosu", "amoria@hospio360.com", "+2348020000001", "SUPER_ADMIN"),
            new Person("20000000-0000-4000-8000-000000000002", "Kola Adeniyi", "kola@hospio360.com", "+2348020000002", "SUPPORT"),
            new Person("20000000-0000-4000-8000-000000000003", "Tobi Lawal", "tobi@hospio360.com", "+2348020000003", "FINANCE_ADMIN"),
            new Person("20000000-0000-4000-8000-000000000004", "Ese Okoro", "ese@hospio360.com", "+2348020000004", "MARKETER"));

    // ---- Simpas Ikeja: four room types, four floors, 120 rooms ---------------------------------------

    public record RoomTypeSeed(String id, String name, String description, BigDecimal basePrice, int adults, int children, String facilities) {
    }

    public static final RoomTypeSeed REGULAR = new RoomTypeSeed("30000000-0000-4000-8000-000000000001", "Regular", "Our standard room with a queen bed", new BigDecimal("35000"), 2, 1, "AC,TV,Wi-Fi,Hot water");
    public static final RoomTypeSeed DELUXE = new RoomTypeSeed("30000000-0000-4000-8000-000000000002", "Deluxe", "A larger room with a king bed and a work desk", new BigDecimal("45000"), 2, 2, "AC,TV,Wi-Fi,Hot water,Minibar,Work desk");
    public static final RoomTypeSeed EXECUTIVE = new RoomTypeSeed("30000000-0000-4000-8000-000000000003", "Executive", "Corner room with a bathtub and lounge access", new BigDecimal("65000"), 2, 2, "AC,TV,Wi-Fi,Hot water,Minibar,Work desk,Bathtub,Lounge access");
    public static final RoomTypeSeed SUITE = new RoomTypeSeed("30000000-0000-4000-8000-000000000004", "Suite", "Separate living room, butler on call", new BigDecimal("120000"), 3, 2, "AC,TV,Wi-Fi,Hot water,Minibar,Living room,Bathtub,Lounge access,Butler");

    public static final List<RoomTypeSeed> ROOM_TYPES = List.of(REGULAR, DELUXE, EXECUTIVE, SUITE);

    public record FloorSeed(String id, String name, String block, String floor, RoomTypeSeed type, int sortOrder) {
    }

    public static final List<FloorSeed> FLOORS = List.of(
            new FloorSeed("40000000-0000-4000-8000-000000000001", "Block A · Floor 1", "A", "1", REGULAR, 1),
            new FloorSeed("40000000-0000-4000-8000-000000000002", "Block A · Floor 2", "A", "2", DELUXE, 2),
            new FloorSeed("40000000-0000-4000-8000-000000000003", "Block A · Floor 3", "A", "3", EXECUTIVE, 3),
            new FloorSeed("40000000-0000-4000-8000-000000000004", "Block B · Floor 1", "B", "1", REGULAR, 4));

    public record RoomSeed(String number, FloorSeed floor, RoomTypeSeed type) {
    }

    /** 30 rooms per floor, numbered A101–A130, A201–A230, A301–A330 (the last eight are suites) and B101–B130. */
    public static List<RoomSeed> rooms() {
        List<RoomSeed> rooms = new ArrayList<>(120);
        for (FloorSeed f : FLOORS) {
            for (int i = 1; i <= 30; i++) {
                String number = f.block() + f.floor() + String.format("%02d", i);
                boolean suite = "A".equals(f.block()) && "3".equals(f.floor()) && i > 22;
                rooms.add(new RoomSeed(number, f, suite ? SUITE : f.type()));
            }
        }
        return rooms;
    }
}
