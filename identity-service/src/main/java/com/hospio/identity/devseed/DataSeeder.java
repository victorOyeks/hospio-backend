package com.hospio.identity.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test accounts for the example business (see {@link SeedFixtures}), created on start-up when
 * {@code hospio.seed.enabled=true}. Only accounts that do not exist yet are created, so a password a
 * tester changed stays changed; a missing role grant is added to an existing account.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        // The tenant must be bound before the transaction opens its connection: the row-level policies read
        // the session settings the data source sets at connection time, so the transaction starts inside runWith.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int created = TenantContext.runWith(Tenant.system("seed"), () -> tx.execute(status -> {
            int n = 0;
            n += seed(SeedFixtures.OWNER, SeedFixtures.BUSINESS_ID, null);
            n += seed(SeedFixtures.MANAGER, SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID);
            for (SeedFixtures.Person p : SeedFixtures.STAFF) {
                n += seed(p, SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID);
            }
            for (SeedFixtures.Person p : SeedFixtures.ADMINS) {
                n += seed(p, null, null);
            }
            return n;
        }));
        if (created > 0) {
            log.info("Seeded {} test accounts (owner, manager, staff, admins) with password '{}' and PIN {}",
                    created, SeedFixtures.SEED_PASSWORD, SeedFixtures.SEED_PIN);
        } else {
            log.info("Test accounts already present; nothing seeded");
        }
    }

    /** @return 1 when the account was created, 0 when it already existed. */
    private int seed(SeedFixtures.Person p, String businessId, String hotelId) {
        int created = 0;
        UserAccount user = users.findById(p.userId()).orElse(null);
        if (user == null) {
            user = UserAccount.builder().businessId(businessId).fullName(p.fullName()).email(p.email()).phone(p.phone()).build();
            user.setId(p.userId());
            user.setPasswordHash(passwordEncoder.encode(SeedFixtures.SEED_PASSWORD));
            user.setPinHash(passwordEncoder.encode(SeedFixtures.SEED_PIN));
            user.setMustChangePassword(false);
            user.setStatus(UserAccount.Status.ACTIVE);
            user = users.save(user);
            created = 1;
        }
        Role role = Role.valueOf(p.role());
        String userId = user.getId();
        grants.findByUserIdAndHotelIdAndRole(userId, hotelId, role).orElseGet(() ->
                grants.save(RoleGrant.builder().userId(userId).businessId(businessId).hotelId(hotelId)
                        .role(role).level(AccessLevel.FULL).build()));
        return created;
    }
}
