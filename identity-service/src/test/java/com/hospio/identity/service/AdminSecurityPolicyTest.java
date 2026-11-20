package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.Role;
import com.hospio.identity.client.PlatformClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The admin security settings as identity reads them: a short cache, the last value kept when platform can't be read, defaults off / 12 h. */
class AdminSecurityPolicyTest {

    private final PlatformClient platform = mock(PlatformClient.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T09:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final AdminSecurityPolicy policy = new AdminSecurityPolicy(platform, 30, 10, clock);

    private void later(long seconds) {
        now.set(now.get().plusSeconds(seconds));
    }

    @Test
    void nothingReadYetMeansTheDefaultsOffAnd12Hours() {
        when(platform.securitySettings()).thenReturn(Optional.empty());
        assertThat(policy.settings()).isEqualTo(new AdminSecurityPolicy.Settings(false, 12));
        assertThat(policy.twoStepSetupOnly(List.of(Role.SUPER_ADMIN), false)).isFalse();
    }

    @Test
    void unreadableSettingKeepsTheLastValueRead() {
        when(platform.securitySettings()).thenReturn(Optional.of(new PlatformClient.SecuritySettings(true, 4)));
        assertThat(policy.settings()).isEqualTo(new AdminSecurityPolicy.Settings(true, 4));

        when(platform.securitySettings()).thenReturn(Optional.empty());
        later(31);
        // platform down: the setting that was on stays on (fail safe), and so does the session length
        assertThat(policy.settings()).isEqualTo(new AdminSecurityPolicy.Settings(true, 4));
        assertThat(policy.twoStepSetupOnly(List.of(Role.SUPPORT), false)).isTrue();
        // retried after 10 s, not on every sign-in
        later(5);
        policy.settings();
        verify(platform, times(2)).securitySettings();
        later(6);
        policy.settings();
        verify(platform, times(3)).securitySettings();
    }

    @Test
    void cachedFor30Seconds() {
        when(platform.securitySettings()).thenReturn(Optional.of(new PlatformClient.SecuritySettings(false, 24)));
        policy.settings();
        later(29);
        policy.settings();
        verify(platform, times(1)).securitySettings();
        when(platform.securitySettings()).thenReturn(Optional.of(new PlatformClient.SecuritySettings(true, 24)));
        later(2);
        assertThat(policy.settings().require2Step()).isTrue();
    }

    @Test
    void onlyThe3SessionLengthsAreTaken() {
        assertThat(AdminSecurityPolicy.hours(4)).isEqualTo(4);
        assertThat(AdminSecurityPolicy.hours(12)).isEqualTo(12);
        assertThat(AdminSecurityPolicy.hours(24)).isEqualTo(24);
        assertThat(AdminSecurityPolicy.hours(1)).isEqualTo(12);
        assertThat(AdminSecurityPolicy.hours(null)).isEqualTo(12);
    }

    @Test
    void staffAreThePlatformRolesNotMarketersOrHotels() {
        assertThat(AdminSecurityPolicy.isPlatformStaff(List.of(Role.SUPER_ADMIN))).isTrue();
        assertThat(AdminSecurityPolicy.isPlatformStaff(List.of(Role.SUPPORT))).isTrue();
        assertThat(AdminSecurityPolicy.isPlatformStaff(List.of(Role.FINANCE_ADMIN))).isTrue();
        assertThat(AdminSecurityPolicy.isPlatformStaff(List.of(Role.MARKETER, Role.SUPPORT))).isTrue();
        assertThat(AdminSecurityPolicy.isPlatformStaff(List.of(Role.MARKETER))).isFalse();
        assertThat(AdminSecurityPolicy.isPlatformStaff(List.of(Role.OWNER, Role.MANAGER, Role.FINANCE))).isFalse();
    }
}
