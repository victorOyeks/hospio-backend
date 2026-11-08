package com.hospio.tenant.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Hotel;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Royal Suites Ltd with Simpas Ikeja, using the fixed ids every service shares; created when missing and hospio.seed.enabled=true. */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final BusinessRepository businesses;
    private final HotelRepository hotels;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        // Bind the tenant first, then open the transaction, so the connection carries the platform scope.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(Tenant.system("seed"), () -> tx.executeWithoutResult(status -> {
            if (businesses.findById(SeedFixtures.BUSINESS_ID).isEmpty()) {
                Business b = Business.builder().name(SeedFixtures.BUSINESS_NAME).legalName("Royal Suites Limited")
                        .ownerUserId(SeedFixtures.OWNER.userId()).ownerName(SeedFixtures.OWNER.fullName())
                        .ownerEmail(SeedFixtures.OWNER.email()).ownerPhone(SeedFixtures.OWNER.phone())
                        .stage(Business.Stage.LIVE).feeModel(Business.FeeModel.PERCENT_OF_PAYMENTS)
                        .feeValue(new java.math.BigDecimal("2.50")).country("Nigeria").build();
                b.setId(SeedFixtures.BUSINESS_ID);
                businesses.save(b);
            }
            if (hotels.findById(SeedFixtures.HOTEL_ID).isEmpty()) {
                Hotel h = Hotel.builder().businessId(SeedFixtures.BUSINESS_ID).name(SeedFixtures.HOTEL_NAME).city("Lagos")
                        .address("12 Allen Avenue, Ikeja").timezone("Africa/Lagos").roomCount(120).build();
                h.setId(SeedFixtures.HOTEL_ID);
                hotels.save(h);
            }
        }));
        log.info("Seeded {} / {}", SeedFixtures.BUSINESS_NAME, SeedFixtures.HOTEL_NAME);
    }
}
