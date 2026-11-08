package com.hospio.payment.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.enums.Role;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.entity.PosRoleAssignment;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.repository.PosRoleAssignmentRepository;
import com.hospio.payment.repository.PosTerminalRepository;
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
 * POS terminals for Simpas Ikeja, one per role that takes payments, on the "logging" provider: card
 * and transfer requests are accepted and stay PROCESSING until a webhook (or Verify) settles them, which
 * is what lets testers walk the POS flow without a real terminal. Created when missing; a terminal a
 * manager renamed or re-assigned in Hotel setup is left alone.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private record TerminalSeed(String id, String name, String ref, Role role) {
    }

    private static final List<TerminalSeed> TERMINALS = List.of(
            new TerminalSeed("50000000-0000-4000-8000-000000000001", "Front desk POS", "LOG-FD-01", Role.FRONT_OFFICE),
            new TerminalSeed("50000000-0000-4000-8000-000000000002", "Bar POS", "LOG-BAR-01", Role.BAR),
            new TerminalSeed("50000000-0000-4000-8000-000000000003", "Restaurant POS", "LOG-KIT-01", Role.KITCHEN),
            new TerminalSeed("50000000-0000-4000-8000-000000000004", "Laundry POS", "LOG-LDY-01", Role.LAUNDRY));

    private final PosTerminalRepository terminals;
    private final PosRoleAssignmentRepository assignments;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        Tenant tenant = Tenant.systemFor(SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID, "seed", "Seed");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(status -> {
            int created = 0;
            for (TerminalSeed t : TERMINALS) {
                if (terminals.findById(t.id()).isEmpty()) {
                    PosTerminal terminal = PosTerminal.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID)
                            .name(t.name()).provider("logging").terminalRef(t.ref()).build();
                    terminal.setId(t.id());
                    terminals.save(terminal);
                    created++;
                }
                if (assignments.findByHotelIdAndRole(SeedFixtures.HOTEL_ID, t.role()).isEmpty()) {
                    assignments.save(PosRoleAssignment.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID)
                            .role(t.role()).terminalId(t.id()).build());
                }
            }
            if (created > 0) {
                log.info("Seeded {} POS terminals (logging provider) for {}", created, SeedFixtures.HOTEL_NAME);
            } else {
                log.info("POS terminals for {} already present; nothing seeded", SeedFixtures.HOTEL_NAME);
            }
        }));
    }
}
