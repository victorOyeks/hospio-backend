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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** dev/test only: Royal Suites Ltd with Simpas Ikeja, using the fixed ids every service shares. */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final BusinessRepository businesses;
    private final HotelRepository hotels;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        TenantContext.runWith(Tenant.system("seed"), () -> {
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
        });
        log.info("Seeded {} / {}", SeedFixtures.BUSINESS_NAME, SeedFixtures.HOTEL_NAME);
    }
}
