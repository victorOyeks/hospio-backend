package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.dto.StaffAccountRequests;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Owner portal · Staff: who may suspend, pause, reinstate and edit whom, and what the account looks like after. */
class StaffAccountServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final UserService userService = mock(UserService.class);
    private final StaffAccountService service = new StaffAccountService(users, grants, refresh, events, userService, mock(SharedDeviceService.class));

    private final List<RoleGrant> adaGrants = new ArrayList<>();
    private UserAccount ada;
    private RefreshToken token;

    @BeforeEach
    void setUp() {
        ada = account("ada", "b1", "Ada Okeke", "ada@x.ng");
        adaGrants.add(grant("ada", Role.FRONT_OFFICE, "h1"));
        when(users.findById("ada")).thenReturn(Optional.of(ada));
        when(grants.findByUserId("ada")).thenAnswer(i -> adaGrants);
        when(users.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
        token = new RefreshToken();
        when(refresh.findByUserIdAndRevokedAtIsNull("ada")).thenReturn(List.of(token));
        when(userService.get(anyString())).thenReturn(mock(UserResponse.class));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UserAccount account(String id, String business, String name, String email) {
        UserAccount u = UserAccount.builder().businessId(business).fullName(name).email(email).build();
        u.setId(id);
        return u;
    }

    private static RoleGrant grant(String user, Role role, String hotel) {
        RoleGrant g = RoleGrant.builder().userId(user).businessId("b1").hotelId(hotel).role(role).level(AccessLevel.FULL).build();
        g.setId(user + "-" + role);
        return g;
    }

    private static void signInAs(String id, String business, Role role, String... hotels) {
        HospioUserDetails u = new HospioUserDetails(id, id + "@x.ng", "Godwin Kwin", business, List.of(hotels),
                Map.of(role, AccessLevel.FULL), role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test
    void ownerSuspendsWithAReasonSignsThePersonOutAndTellsTheManagers() {
        signInAs("godwin", "b1", Role.OWNER, "h1", "h2");
        service.suspend("ada", new StaffAccountRequests.Suspend("Cash went missing at the desk"));

        assertThat(ada.getStatus()).isEqualTo(UserAccount.Status.SUSPENDED);
        assertThat(ada.getStatusReason()).isEqualTo("Cash went missing at the desk");
        assertThat(ada.getStatusChangedBy()).isEqualTo("Godwin Kwin");
        assertThat(token.getRevokedAt()).isNotNull();
        ArgumentCaptor<AccessChangedEvent> changed = ArgumentCaptor.forClass(AccessChangedEvent.class);
        verify(events).publish(eq(KafkaTopics.ACCESS_CHANGED), changed.capture());
        assertThat(changed.getValue().getChange()).isEqualTo("SUSPENDED");
        assertThat(changed.getValue().getUserId()).isEqualTo("ada");
        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        assertThat(notice.getValue().getRoles()).containsExactly("MANAGER");
        assertThat(notice.getValue().getHotelId()).isEqualTo("h1");
    }

    @Test
    void suspendingNeedsAReasonAndHappensOnce() {
        signInAs("godwin", "b1", Role.OWNER, "h1");
        assertThatThrownBy(() -> service.suspend("ada", new StaffAccountRequests.Suspend(" ")))
                .isInstanceOf(BadRequestException.class).hasMessage("Say why Ada Okeke is suspended");
        ada.setStatus(UserAccount.Status.SUSPENDED);
        assertThatThrownBy(() -> service.suspend("ada", new StaffAccountRequests.Suspend("Again")))
                .isInstanceOf(BadRequestException.class).hasMessage("Ada Okeke is already suspended");
    }

    @Test
    void pauseForSevenDaysEndsAtLagosMidnightAfterTheSeventhDay() {
        signInAs("godwin", "b1", Role.OWNER, "h1");
        service.pause("ada", new StaffAccountRequests.Pause(7, "Family emergency"));

        LocalDate last = LocalDate.now(AccessDates.LAGOS).plusDays(6);
        assertThat(ada.getStatus()).isEqualTo(UserAccount.Status.PAUSED);
        assertThat(ada.getPausedUntil()).isEqualTo(AccessDates.endOf(last));
        assertThat(token.getRevokedAt()).isNotNull();
        ArgumentCaptor<AccessChangedEvent> changed = ArgumentCaptor.forClass(AccessChangedEvent.class);
        verify(events).publish(eq(KafkaTopics.ACCESS_CHANGED), changed.capture());
        assertThat(changed.getValue().getChange()).isEqualTo("PAUSED_UNTIL");
        assertThat(changed.getValue().getDetail()).isEqualTo(last.toString());
    }

    @Test
    void aSuspendedPersonIsReinstatedBeforeBeingPausedAndAPauseIsNotStacked() {
        signInAs("godwin", "b1", Role.OWNER, "h1");
        ada.setStatus(UserAccount.Status.SUSPENDED);
        assertThatThrownBy(() -> service.pause("ada", new StaffAccountRequests.Pause(7, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Ada Okeke is suspended. Reinstate them first");
        ada.setStatus(UserAccount.Status.PAUSED);
        ada.setPausedUntil(AccessDates.endOf(LocalDate.of(2099, 1, 1)));
        assertThatThrownBy(() -> service.pause("ada", new StaffAccountRequests.Pause(7, null)))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("Ada Okeke is already paused until");
    }

    @Test
    void reinstateClearsThePauseAndOnlyWhenThereIsOne() {
        signInAs("godwin", "b1", Role.OWNER, "h1");
        assertThatThrownBy(() -> service.reinstate("ada")).isInstanceOf(BadRequestException.class)
                .hasMessage("Ada Okeke is not suspended or paused");
        ada.setStatus(UserAccount.Status.PAUSED);
        ada.setPausedUntil(Instant.now().plusSeconds(3600));
        ada.setStatusReason("Leave");
        service.reinstate("ada");
        assertThat(ada.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(ada.getPausedUntil()).isNull();
        assertThat(ada.getStatusReason()).isNull();
    }

    @Test
    void theJobEndsPausesWhoseLastDayHasPassed() {
        UserAccount due = account("bola", "b1", "Bola Ade", "bola@x.ng");
        due.setStatus(UserAccount.Status.PAUSED);
        due.setPausedUntil(Instant.now().minusSeconds(60));
        when(users.findByStatusAndPausedUntilLessThanEqual(eq(UserAccount.Status.PAUSED), any())).thenReturn(List.of(due));

        assertThat(service.resumeDuePauses()).isEqualTo(1);
        assertThat(due.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(due.getPausedUntil()).isNull();
        verify(events, atLeastOnce()).publish(eq(KafkaTopics.ACCESS_CHANGED), any());
    }

    @Test
    void aPauseThatRanOutReadsAsActive() {
        ada.setStatus(UserAccount.Status.PAUSED);
        ada.setPausedUntil(Instant.now().minusSeconds(1));
        assertThat(ada.pauseHasEnded(Instant.now())).isTrue();
        UserResponse r = new UserResponse("ada", "Ada", "a@x", null, "b1", "PAUSED", false, false, false, false, null, null, null, List.of(),
                UserResponse.InviteStatus.NONE).withAccount(ada);
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(r.pausedUntil()).isNull();
    }

    @Test
    void nobodyActsOnThemselvesTheOwnerOrAnotherBusiness() {
        signInAs("godwin", "b1", Role.OWNER, "h1");
        assertThatThrownBy(() -> service.suspend("godwin", new StaffAccountRequests.Suspend("x y z")))
                .isInstanceOf(ForbiddenException.class).hasMessageStartingWith("You can't suspend your own account here");

        UserAccount owner = account("owner2", "b1", "Co Owner", "co@x.ng");
        when(users.findById("owner2")).thenReturn(Optional.of(owner));
        when(grants.findByUserId("owner2")).thenReturn(List.of(grant("owner2", Role.OWNER, null)));
        assertThatThrownBy(() -> service.pause("owner2", new StaffAccountRequests.Pause(7, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only H.M.S can change the owner's account");

        UserAccount other = account("zed", "b2", "Zed Other", "zed@x.ng");
        when(users.findById("zed")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.suspend("zed", new StaffAccountRequests.Suspend("x y z")))
                .isInstanceOf(ForbiddenException.class).hasMessage("That person is not in your business");
        verify(users, never()).save(other);
    }

    @Test
    void aManagerActsOnlyOnTheirHotelsStaffAndNeverOnAManager() {
        signInAs("tunde", "b1", Role.MANAGER, "h2");
        assertThatThrownBy(() -> service.suspend("ada", new StaffAccountRequests.Suspend("x y z")))
                .isInstanceOf(ForbiddenException.class).hasMessage("That person does not work at one of your hotels");

        signInAs("tunde", "b1", Role.MANAGER, "h1");
        adaGrants.add(grant("ada", Role.MANAGER, "h1"));
        assertThatThrownBy(() -> service.pause("ada", new StaffAccountRequests.Pause(3, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only the owner can pause a manager");
    }

    @Test
    void staffCannotActAtAll() {
        signInAs("victor", "b1", Role.FRONT_OFFICE, "h1");
        assertThatThrownBy(() -> service.reinstate("ada")).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only a manager or the owner can reinstate a staff member");
    }

    @Test
    void detailsChangeOnlyWhatIsSentAndRefuseATakenEmail() {
        signInAs("godwin", "b1", Role.OWNER, "h1");
        when(users.findByEmailIgnoreCase("taken@x.ng")).thenReturn(Optional.of(account("x", "b1", "X", "taken@x.ng")));
        assertThatThrownBy(() -> service.updateDetails("ada", new StaffAccountRequests.Details(null, null, "taken@x.ng", null)))
                .isInstanceOf(ConflictException.class).hasMessage("Someone already signs in with taken@x.ng");
        assertThatThrownBy(() -> service.updateDetails("ada", new StaffAccountRequests.Details("Ada Okeke", null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Nothing to change: the details are the same as before");

        service.updateDetails("ada", new StaffAccountRequests.Details("Ada N. Okeke", null, null, "Night auditor"));
        assertThat(ada.getFullName()).isEqualTo("Ada N. Okeke");
        assertThat(ada.getJobTitle()).isEqualTo("Night auditor");
        assertThat(ada.getEmail()).isEqualTo("ada@x.ng");
    }

    @Test
    void aManagerResetsAStaffPinWhichClearsItAndItsLock() {
        signInAs("tunde", "b1", Role.MANAGER, "h1");
        ada.setPinHash("hash");
        ada.setFailedPinAttempts(3);
        ada.setLockedUntil(Instant.now().plusSeconds(600));
        service.resetPin("ada");
        assertThat(ada.getPinHash()).isNull();
        assertThat(ada.getFailedPinAttempts()).isZero();
        assertThat(ada.getLockedUntil()).isNull();
        assertThatThrownBy(() -> service.resetPin("ada")).isInstanceOf(BadRequestException.class).hasMessageContaining("no PIN");
    }

    @Test
    void staffCannotResetPins() {
        signInAs("segun", "b1", Role.BAR, "h1");
        ada.setPinHash("hash");
        assertThatThrownBy(() -> service.resetPin("ada")).isInstanceOf(ForbiddenException.class);
    }
}
