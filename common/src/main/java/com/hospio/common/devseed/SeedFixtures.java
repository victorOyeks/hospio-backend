package com.hospio.common.devseed;

import java.util.List;

/**
 * dev/test only. The example business from the design handoff so every service seeds the same ids
 * and the apps can be exercised end to end: Royal Suites Ltd, Simpas Ikeja, owner Godwin Kwin, GM
 * Tunde Bakare and the named staff. Ids are fixed so services agree without asking each other.
 */
public final class SeedFixtures {

    private SeedFixtures() {
    }

    public static final String SEED_PASSWORD = "Hospio123!";
    public static final String SEED_PIN = "1234";

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
}
