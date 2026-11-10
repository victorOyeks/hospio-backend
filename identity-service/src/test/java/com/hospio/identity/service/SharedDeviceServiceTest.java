package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.SharedDeviceDtos;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.SharedDevice;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.SharedDeviceRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who may set up and manage a shared device, and who its lock screen shows (docs/shared-devices.md). */
class SharedDeviceServiceTest {

    private final SharedDeviceRepository repo = mock(SharedDeviceRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final SharedDeviceService service = new SharedDeviceService(repo, users, grants, refresh, tenant, mock(EventPublisher.class));
    private final List<RoleGrant> hotelGrants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(repo.save(any())).thenAnswer(i -> {
            SharedDevice d = i.getArgument(0);
            if (d.getId() == null) {
                d.setId("d1");
            }
            return d;
        });
        when(tenant.hotel("h1")).thenReturn(Optional.of(new TenantClient.HotelInfo("h1", "b1", "Simpas Ikeja")));
        when(grants.findByHotelId("h1")).thenAnswer(i -> hotelGrants);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static RoleGrant grant(String user, Role role, String hotel, AccessLevel level) {
        RoleGrant g = RoleGrant.builder().userId(user).businessId("b1").hotelId(hotel).role(role).level(level).build();
        g.setId(user + "-" + role);
        return g;
    }

    private void signInAs(String id, Role role, AccessLevel level, String... hotels) {
        HospioUserDetails u = new HospioUserDetails(id, id + "@x.ng", "Caller " + id, "b1", List.of(hotels), Map.of(role, level),
                role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        if (role != Role.OWNER) {
            when(grants.findByUserId(id)).thenReturn(List.of(grant(id, role, hotels[0], level)));
        } else {
            when(grants.findByUserId(id)).thenReturn(List.of(grant(id, role, null, level)));
        }
    }

    private static SharedDeviceDtos.Setup setup(String hotel, String... roles) {
        return new SharedDeviceDtos.Setup("Bar tablet", hotel, List.of(roles), null);
    }

    @Test
    void aManagerSetsUpADeviceAndGetsItsTokenOnce() {
        signInAs("tunde", Role.MANAGER, AccessLevel.FULL, "h1");
        SharedDeviceDtos.SetupResult result = service.setup(setup("h1", "BAR", "KITCHEN"));
        assertThat(result.deviceToken()).hasSizeGreaterThan(30);
        assertThat(result.device().hotelName()).isEqualTo("Simpas Ikeja");
        assertThat(result.device().roles()).containsExactly("KITCHEN", "BAR");
        assertThat(result.device().autoLockMinutes()).isEqualTo(5);
    }

    @Test
    void fullAccessStaffSetUpOnlyForTheirOwnRoles() {
        signInAs("segun", Role.BAR, AccessLevel.FULL, "h1");
        assertThat(service.setup(setup("h1", "BAR")).deviceToken()).isNotBlank();
        assertThatThrownBy(() -> service.setup(setup("h1", "KITCHEN"))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void editOrViewStaffOtherHotelsAndOwnerRolesAreRefused() {
        signInAs("cook", Role.KITCHEN, AccessLevel.EDIT, "h1");
        assertThatThrownBy(() -> service.setup(setup("h1", "KITCHEN"))).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Full access");
        signInAs("tunde", Role.MANAGER, AccessLevel.FULL, "h1");
        assertThatThrownBy(() -> service.setup(setup("h2", "BAR"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.setup(setup("h1", "OWNER"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setup(setup("h1", "SUPER_ADMIN"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theOwnerSetsUpForAnyOfTheirHotels() {
        signInAs("godwin", Role.OWNER, AccessLevel.FULL, "h1", "h2");
        assertThat(service.setup(setup("h1", "FRONT_OFFICE")).device().hotelId()).isEqualTo("h1");
    }

    @Test
    void lockScreenShowsTheHotelsPeopleWithAPinAndARoleThatFits() {
        SharedDevice bar = SharedDevice.builder().businessId("b1").hotelId("h1").hotelName("Simpas Ikeja").name("Bar tablet").roles("BAR")
                .tokenHash(Hashing.sha256("tok")).build();
        bar.setId("d1");
        when(repo.findByTokenHash(Hashing.sha256("tok"))).thenReturn(Optional.of(bar));
        hotelGrants.add(grant("segun", Role.BAR, "h1", AccessLevel.FULL));
        hotelGrants.add(grant("nopin", Role.BAR, "h1", AccessLevel.FULL));
        hotelGrants.add(grant("paused", Role.BAR, "h1", AccessLevel.FULL));
        hotelGrants.add(grant("cook", Role.KITCHEN, "h1", AccessLevel.FULL));
        RoleGrant held = grant("held", Role.BAR, "h1", AccessLevel.FULL);
        held.setPaused(true);
        hotelGrants.add(held);
        UserAccount segun = person("segun", "Segun", "b1", "x");
        UserAccount nopin = person("nopin", "No Pin", "b1", null);
        UserAccount paused = person("paused", "Paused", "b1", "x");
        paused.setStatus(UserAccount.Status.PAUSED);
        paused.setPausedUntil(Instant.now().plusSeconds(3600));
        when(users.findAllById(any())).thenAnswer(i -> {
            List<UserAccount> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                for (UserAccount u : List.of(segun, nopin, paused)) {
                    if (u.getId().equals(id)) {
                        out.add(u);
                    }
                }
            }
            return out;
        });

        SharedDeviceDtos.LockScreen screen = service.lockScreen("tok");
        assertThat(screen.hotelName()).isEqualTo("Simpas Ikeja");
        assertThat(screen.people()).extracting(SharedDeviceDtos.Person::fullName).containsExactly("Segun");
    }

    @Test
    void aRevokedDeviceShowsNothingAndItsSessionsEnd() {
        signInAs("tunde", Role.MANAGER, AccessLevel.FULL, "h1");
        SharedDevice bar = SharedDevice.builder().businessId("b1").hotelId("h1").name("Bar tablet").roles("BAR")
                .tokenHash(Hashing.sha256("tok")).build();
        bar.setId("d1");
        when(repo.findById("d1")).thenReturn(Optional.of(bar));
        when(repo.findByTokenHash(Hashing.sha256("tok"))).thenReturn(Optional.of(bar));
        RefreshToken session = RefreshToken.builder().userId("segun").deviceId("d1").tokenHash("h").expiresAt(Instant.now().plusSeconds(60)).build();
        when(refresh.findByDeviceIdAndRevokedAtIsNull("d1")).thenReturn(List.of(session));

        service.revoke("d1");
        assertThat(bar.isRevoked()).isTrue();
        assertThat(session.getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> service.lockScreen("tok")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void onlyTheHotelsManagersManageItsDevices() {
        signInAs("segun", Role.BAR, AccessLevel.FULL, "h1");
        SharedDevice bar = SharedDevice.builder().businessId("b1").hotelId("h1").name("Bar tablet").roles("BAR").tokenHash("x").build();
        bar.setId("d1");
        when(repo.findById("d1")).thenReturn(Optional.of(bar));
        assertThatThrownBy(() -> service.revoke("d1")).isInstanceOf(ForbiddenException.class);
        signInAs("tunde", Role.MANAGER, AccessLevel.FULL, "h1");
        assertThat(service.change("d1", new SharedDeviceDtos.Change("Pool bar", null, 2)).autoLockMinutes()).isEqualTo(2);
        assertThat(bar.getName()).isEqualTo("Pool bar");
    }

    private static UserAccount person(String id, String name, String business, String pinHash) {
        UserAccount u = UserAccount.builder().businessId(business).fullName(name).email(id + "@x.ng").pinHash(pinHash).build();
        u.setId(id);
        return u;
    }
}
