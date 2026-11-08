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
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * dev/test only: the example business from the design handoff, so the apps can be exercised end to
 * end. Idempotent; always resets passwords and PINs to the seed values.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        TenantContext.runWith(Tenant.system("seed"), () -> {
            seed(SeedFixtures.OWNER, SeedFixtures.BUSINESS_ID, null);
            seed(SeedFixtures.MANAGER, SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID);
            SeedFixtures.STAFF.forEach(p -> seed(p, SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID));
            SeedFixtures.ADMINS.forEach(p -> seed(p, null, null));
        });
        log.info("Seeded owner, manager, {} staff and {} admins - password {} / PIN {}",
                SeedFixtures.STAFF.size(), SeedFixtures.ADMINS.size(), SeedFixtures.SEED_PASSWORD, SeedFixtures.SEED_PIN);
    }

    private void seed(SeedFixtures.Person p, String businessId, String hotelId) {
        UserAccount user = users.findById(p.userId()).orElseGet(() -> {
            UserAccount u = UserAccount.builder().businessId(businessId).fullName(p.fullName()).email(p.email()).phone(p.phone()).build();
            u.setId(p.userId());
            return u;
        });
        user.setPasswordHash(passwordEncoder.encode(SeedFixtures.SEED_PASSWORD));
        user.setPinHash(passwordEncoder.encode(SeedFixtures.SEED_PIN));
        user.setMustChangePassword(false);
        user.setStatus(UserAccount.Status.ACTIVE);
        users.save(user);
        Role role = Role.valueOf(p.role());
        grants.findByUserIdAndHotelIdAndRole(user.getId(), hotelId, role).orElseGet(() ->
                grants.save(RoleGrant.builder().userId(user.getId()).businessId(businessId).hotelId(hotelId)
                        .role(role).level(AccessLevel.FULL).build()));
    }
}
