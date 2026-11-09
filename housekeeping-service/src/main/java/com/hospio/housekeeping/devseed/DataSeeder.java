package com.hospio.housekeeping.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.entity.LaundryPrice;
import com.hospio.housekeeping.repository.LaundryPriceRepository;
import com.hospio.housekeeping.service.LaundryRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The guest laundry price list for Simpas Ikeja (docs/laundry-maintenance.md · Seed); names already on the list are left alone. */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private record Seed(String name, String category, int price) {
    }

    private static final List<Seed> PRICES = List.of(new Seed("Shirt", "WASH_IRON", 1500), new Seed("Trousers", "WASH_IRON", 1500),
            new Seed("Dress", "WASH_IRON", 2500), new Seed("Suit (2-piece)", "DRY_CLEAN", 6000), new Seed("Native attire", "WASH_IRON", 3000),
            new Seed("Bedsheet (guest)", "WASH_IRON", 2000), new Seed("Underwear (pair)", "WASH_IRON", 500));

    private final LaundryPriceRepository prices;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        Tenant tenant = Tenant.systemFor(SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID, "seed", "Seed");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(status -> {
            Set<String> have = prices.findByHotelIdOrderBySortOrderAscNameAsc(SeedFixtures.HOTEL_ID).stream()
                    .map(p -> p.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            int created = 0;
            int order = 0;
            for (Seed s : PRICES) {
                order++;
                if (have.contains(s.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                BigDecimal price = BigDecimal.valueOf(s.price());
                prices.save(LaundryPrice.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(s.name()).category(s.category())
                        .price(price).expressPrice(LaundryRules.defaultExpress(price)).sortOrder(order).build());
                created++;
            }
            log.info("Laundry price list for {}: {} added", SeedFixtures.HOTEL_NAME, created);
        }));
    }
}
