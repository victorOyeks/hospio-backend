package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.exception.GoneException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AcceptInviteRequest;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.entity.Invite;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.InviteRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Only the SHA-256 of an invite token is stored; it lasts seven days; a used or expired one answers 410. */
class InviteServiceTest {

    private final InviteRepository invites = mock(InviteRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final InviteService service = new InviteService(invites, users, grants, tenant, encoder, tokenIssuer, events);

    InviteServiceTest() {
        ReflectionTestUtils.setField(service, "inviteDays", 7L);
        when(invites.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserAccount tunde() {
        UserAccount u = UserAccount.builder().businessId("b1").fullName("Tunde").email("tunde@x.ng").mustChangePassword(true)
                .temporaryPasswordExpiresAt(Instant.now().plusSeconds(3600)).build();
        u.setId("u1");
        return u;
    }

    @Test
    void issueStoresOnlyTheHashAndASevenDayExpiry() {
        Instant before = Instant.now();
        InviteService.Issued issued = service.issue(tunde(), "admin");

        ArgumentCaptor<Invite> saved = ArgumentCaptor.forClass(Invite.class);
        verify(invites).save(saved.capture());
        assertThat(issued.token()).isNotBlank();
        assertThat(saved.getValue().getTokenHash()).isEqualTo(Hashing.sha256(issued.token())).isNotEqualTo(issued.token());
        assertThat(saved.getValue().getBusinessId()).isEqualTo("b1");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
        assertThat(Duration.between(before, issued.expiresAt()).toDays()).isBetween(6L, 7L);
        assertThat(InviteService.statusOf(saved.getValue())).isEqualTo(UserResponse.InviteStatus.PENDING);
    }

    @Test
    void acceptingSetsThePasswordClearsTheTemporaryOneAndSignsIn() {
        UserAccount user = tunde();
        Invite invite = Invite.builder().userId("u1").businessId("b1").tokenHash(Hashing.sha256("raw-token"))
                .expiresAt(Instant.now().plusSeconds(600)).build();
        when(invites.findByTokenHash(Hashing.sha256("raw-token"))).thenReturn(Optional.of(invite));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(tokenIssuer.issue(user)).thenReturn(new AuthResponse("a", "r", 3600, List.of("MANAGER"), false, false));

        AuthResponse response = service.accept(new AcceptInviteRequest("raw-token", "NewPassword1"));

        assertThat(response.accessToken()).isEqualTo("a");
        assertThat(encoder.matches("NewPassword1", user.getPasswordHash())).isTrue();
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getTemporaryPasswordExpiresAt()).isNull();
        assertThat(invite.isAccepted()).isTrue();
        assertThat(InviteService.statusOf(invite)).isEqualTo(UserResponse.InviteStatus.ACCEPTED);
        verify(events).publish(any(), any());
    }

    @Test
    void expiredOrUsedInvitesAreGoneAndUnknownOnesNotFound() {
        Invite expired = Invite.builder().userId("u1").tokenHash(Hashing.sha256("old")).expiresAt(Instant.now().minusSeconds(1)).build();
        Invite used = Invite.builder().userId("u1").tokenHash(Hashing.sha256("used")).expiresAt(Instant.now().plusSeconds(600))
                .acceptedAt(Instant.now().minusSeconds(60)).build();
        when(invites.findByTokenHash(Hashing.sha256("old"))).thenReturn(Optional.of(expired));
        when(invites.findByTokenHash(Hashing.sha256("used"))).thenReturn(Optional.of(used));
        when(invites.findByTokenHash(Hashing.sha256("nope"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("old")).isInstanceOf(GoneException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.get("used")).isInstanceOf(GoneException.class).hasMessageContaining("already used");
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(InviteService.statusOf(expired)).isEqualTo(UserResponse.InviteStatus.EXPIRED);
    }
}
