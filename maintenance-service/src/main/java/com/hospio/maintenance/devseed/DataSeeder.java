package com.hospio.maintenance.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.maintenance.entity.Asset;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.entity.ScheduledJob;
import com.hospio.maintenance.repository.AssetRepository;
import com.hospio.maintenance.repository.ScheduledJobRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Three assets and two preventive schedules for Simpas Ikeja (docs/laundry-maintenance.md · Seed), created when missing. */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    static final String AC_101 = "82000000-0000-4000-8000-000000000001";
    static final String GENERATOR = "82000000-0000-4000-8000-000000000002";
    static final String POOL_PUMP = "82000000-0000-4000-8000-000000000003";
    static final String AC_SERVICE = "83000000-0000-4000-8000-000000000001";
    static final String GENERATOR_SERVICE = "83000000-0000-4000-8000-000000000002";

    private final AssetRepository assets;
    private final ScheduledJobRepository jobs;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        Tenant tenant = Tenant.systemFor(SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID, "seed", "Seed");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(status -> {
            int created = 0;
            created += asset(AC_101, "Block A AC units", Repair.Category.AC, "Block A corridor");
            created += asset(GENERATOR, "Generator 1 (60 kVA)", Repair.Category.ELECTRICAL, "Generator house");
            created += asset(POOL_PUMP, "Pool pump", Repair.Category.PLUMBING, "Pool");
            LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
            created += job(AC_SERVICE, "AC service", AC_101, "Block A corridor", Repair.Category.AC, 90, today.plusDays(14));
            created += job(GENERATOR_SERVICE, "Generator service", GENERATOR, "Generator house", Repair.Category.ELECTRICAL, 30, today.plusDays(7));
            log.info(created > 0 ? "Seeded {} maintenance assets and schedules" : "Maintenance assets and schedules already present{}", created > 0 ? created : "");
        }));
    }

    private int asset(String id, String name, Repair.Category category, String area) {
        if (assets.findById(id).isPresent()
                || assets.findByHotelIdOrderByName(SeedFixtures.HOTEL_ID).stream().anyMatch(a -> a.getName().equalsIgnoreCase(name))) {
            return 0;
        }
        Asset a = Asset.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).name(name).category(category).area(area).build();
        a.setId(id);
        assets.save(a);
        return 1;
    }

    private int job(String id, String title, String assetId, String area, Repair.Category category, int every, LocalDate first) {
        if (jobs.findById(id).isPresent() || assets.findById(assetId).isEmpty()) {
            return 0;
        }
        ScheduledJob j = ScheduledJob.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID).title(title).assetId(assetId)
                .area(area).category(category).everyDays(every).nextDue(first).build();
        j.setId(id);
        jobs.save(j);
        return 1;
    }
}
