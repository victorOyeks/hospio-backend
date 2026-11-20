package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.platform.repository.PlatformSettingRepository;
import com.hospio.platform.settings.SecuritySettings;
import org.junit.jupiter.api.Test;

/** The security group identity enforces (docs/sign-in.md): off and 12 h on a fresh database; only 4, 12 or 24 hours are saved. */
class SecuritySettingsTest {

    private final PlatformSettingRepository repo = mock(PlatformSettingRepository.class);
    private final PlatformSettingsService service = new PlatformSettingsService(repo, new ObjectMapper());

    @Test
    void freshDatabaseIsOffAnd12Hours() {
        SecuritySettings s = service.securityGroup();
        assertThat(s.require2Step()).isFalse();
        assertThat(s.sessionHours()).isEqualTo(12);
    }

    @Test
    void onlyTheOfferedSessionLengthsAreSaved() {
        for (int hours : new int[] {4, 12, 24}) {
            service.update(new SecuritySettings(null, hours, null, null));
        }
        for (int hours : new int[] {0, 1, 8, 48}) {
            assertThatThrownBy(() -> service.update(new SecuritySettings(null, hours, null, null)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("4, 12 or 24");
        }
    }

    @Test
    void aBadLengthSavesNothing() {
        org.mockito.Mockito.clearInvocations(repo);
        assertThatThrownBy(() -> service.update(new SecuritySettings(true, 2, null, null))).isInstanceOf(BadRequestException.class);
        verify(repo, never()).save(any());
    }
}
