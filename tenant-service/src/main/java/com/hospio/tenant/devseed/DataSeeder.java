package com.hospio.tenant.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Hotel;
import com.hospio.tenant.entity.SetupTemplate;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import com.hospio.tenant.repository.SetupTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Royal Suites Ltd with Simpas Ikeja, using the fixed ids every service shares, and the default
 * "Standard hotel" setup template (the hotel-config catalogue defaults); created when missing and
 * hospio.seed.enabled=true. A seeded business from before source/city existed gets them filled in.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    public static final String STANDARD_TEMPLATE_ID = "50000000-0000-4000-8000-000000000001";

    private final BusinessRepository businesses;
    private final HotelRepository hotels;
    private final SetupTemplateRepository templates;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        // Bind the tenant first, then open the transaction, so the connection carries the platform scope.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(Tenant.system("seed"), () -> tx.executeWithoutResult(status -> {
            Business b = businesses.findById(SeedFixtures.BUSINESS_ID).orElse(null);
            if (b == null) {
                b = Business.builder().name(SeedFixtures.BUSINESS_NAME).legalName("Royal Suites Limited")
                        .ownerUserId(SeedFixtures.OWNER.userId()).ownerName(SeedFixtures.OWNER.fullName())
                        .ownerEmail(SeedFixtures.OWNER.email()).ownerPhone(SeedFixtures.OWNER.phone())
                        .stage(Business.Stage.LIVE).source(Business.Source.BACK_OFFICE).city("Lagos")
                        .feeModel(Business.FeeModel.PERCENT_OF_PAYMENTS)
                        .feeValue(new java.math.BigDecimal("2.50")).country("Nigeria").hotelCount(1).build();
                b.setId(SeedFixtures.BUSINESS_ID);
                businesses.save(b);
            } else if (b.getCity() == null || b.getHotelCount() == 0) {
                b.setCity(b.getCity() == null ? "Lagos" : b.getCity());
                b.setSource(Business.Source.BACK_OFFICE);
                b.setHotelCount(Math.max(1, hotels.countByBusinessId(b.getId())));
                businesses.save(b);
            }
            if (hotels.findById(SeedFixtures.HOTEL_ID).isEmpty()) {
                Hotel h = Hotel.builder().businessId(SeedFixtures.BUSINESS_ID).name(SeedFixtures.HOTEL_NAME).city("Lagos")
                        .address("12 Allen Avenue, Ikeja").timezone("Africa/Lagos").roomCount(120).starRating(3).build();
                h.setId(SeedFixtures.HOTEL_ID);
                hotels.save(h);
            }
            if (templates.findById(STANDARD_TEMPLATE_ID).isEmpty()) {
                SetupTemplate t = SetupTemplate.builder().name("Standard hotel")
                        .description("The Hotel setup defaults every new hotel starts from: check-in and check-out times, payments, "
                                + "taxes, approval limits, reasons, shifts and departments.")
                        .settings(SetupCatalogue.templateJson().toString()).active(true).build();
                t.setId(STANDARD_TEMPLATE_ID);
                templates.save(t);
            }
        }));
        log.info("Seeded {} / {} and the Standard hotel setup template", SeedFixtures.BUSINESS_NAME, SeedFixtures.HOTEL_NAME);
    }
}
