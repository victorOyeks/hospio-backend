package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.DeliveryService;
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
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AccessChangeRequest;
import com.hospio.identity.dto.AccessChangeRequest.Change;
import com.hospio.identity.dto.GrantRoleRequest;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/** Roles &amp; permissions: who may give, change, pause, end and remove a role, and what they are told when not. */
class UserServiceRolesTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final InviteService invites = mock(InviteService.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final UserService service = new UserService(users, grants, mock(PasswordEncoder.class), events, invites, tenant,
            mock(DeliveryComposer.class), mock(DeliveryService.class));

    private final List<RoleGrant> victorGrants = new ArrayList<>();
    private UserAccount victor;

    @BeforeEach
    void setUp() {
        victor = UserAccount.builder().businessId("b1").fullName("Victor Adeyemi").email("victor@x.ng").build();
        victor.setId("victor");
        when(users.findById("victor")).thenReturn(Optional.of(victor));
        victorGrants.add(grant("g-fo", Role.FRONT_OFFICE, AccessLevel.FULL, "h1"));
        when(grants.findByUserId("victor")).thenAnswer(i -> victorGrants);
        when(grants.findById(anyString())).thenAnswer(i -> victorGrants.stream().filter(g -> g.getId().equals(i.getArgument(0))).findFirst());
        when(grants.findByUserIdAndHotelIdAndRole(anyString(), any(), any())).thenAnswer(i -> victorGrants.stream()
                .filter(g -> g.getHotelId() != null && g.getHotelId().equals(i.getArgument(1)) && g.getRole() == i.getArgument(2)).findFirst());
        when(grants.save(any(RoleGrant.class))).thenAnswer(i -> i.getArgument(0));
        when(invites.statusFor(any())).thenReturn(Map.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static RoleGrant grant(String id, Role role, AccessLevel level, String hotel) {
        RoleGrant g = RoleGrant.builder().userId("victor").businessId("b1").hotelId(hotel).role(role).level(level).build();
        g.setId(id);
        return g;
    }

    private static void signInAs(String id, Role role, String... hotels) {
        HospioUserDetails u = new HospioUserDetails(id, id + "@x.ng", "Tunde Bakare", "b1", List.of(hotels),
                Map.of(role, AccessLevel.FULL), role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test
    void managerGivesARoleAtTheirHotelAndThePersonIsTold() {
        signInAs("tunde", Role.MANAGER, "h1");
        service.grantRole("victor", new GrantRoleRequest(Role.KITCHEN, AccessLevel.VIEW, "h1", null, null));

        ArgumentCaptor<RoleGrant> saved = ArgumentCaptor.forClass(RoleGrant.class);
        verify(grants).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.KITCHEN);
        assertThat(saved.getValue().getLevel()).isEqualTo(AccessLevel.VIEW);
        assertThat(saved.getValue().getHotelId()).isEqualTo("h1");
        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        assertThat(notice.getValue().getUserIds()).containsExactly("victor");
        assertThat(notice.getValue().getTitle()).isEqualTo("You now have Kitchen · View only");
    }

    @Test
    void temporaryRoleEndsAtLagosMidnightAfterItsLastDay() {
        signInAs("tunde", Role.MANAGER, "h1");
        LocalDate last = LocalDate.now(AccessDates.LAGOS).plusDays(3);
        service.grantRole("victor", new GrantRoleRequest(Role.BAR, AccessLevel.EDIT, "h1", null, last));

        ArgumentCaptor<RoleGrant> saved = ArgumentCaptor.forClass(RoleGrant.class);
        verify(grants).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(last.plusDays(1).atStartOfDay(AccessDates.LAGOS).toInstant());
        assertThat(AccessDates.lastDay(saved.getValue().getExpiresAt())).isEqualTo(last);
    }

    @Test
    void aLastDayInThePastIsRefused() {
        signInAs("tunde", Role.MANAGER, "h1");
        assertThatThrownBy(() -> service.grantRole("victor", new GrantRoleRequest(Role.BAR, AccessLevel.EDIT, "h1", null,
                LocalDate.now(AccessDates.LAGOS).minusDays(1))))
                .isInstanceOf(BadRequestException.class).hasMessage("The last day must be today or later");
    }

    @Test
    void nonManagersCannotChangeAccess() {
        signInAs("femi", Role.FINANCE, "h1");
        assertThatThrownBy(() -> service.grantRole("victor", new GrantRoleRequest(Role.KITCHEN, AccessLevel.VIEW, "h1", null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only a manager or the owner can change access");
        assertThatThrownBy(() -> service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.PAUSE, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void managersCannotGiveTheOwnerRole() {
        signInAs("tunde", Role.MANAGER, "h1");
        assertThatThrownBy(() -> service.grantRole("victor", new GrantRoleRequest(Role.OWNER, AccessLevel.FULL, null, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("The owner role is given by Hospio360 or by transferring ownership");
    }

    @Test
    void managersGiveRolesOnlyAtTheirOwnHotels() {
        signInAs("tunde", Role.MANAGER, "h1");
        assertThatThrownBy(() -> service.grantRole("victor", new GrantRoleRequest(Role.KITCHEN, AccessLevel.VIEW, "h2", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void noOneChangesTheirOwnAccess() {
        signInAs("victor", Role.MANAGER, "h1");
        assertThatThrownBy(() -> service.grantRole("victor", new GrantRoleRequest(Role.KITCHEN, AccessLevel.VIEW, "h1", null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("You can't change your own access. Ask the owner or another manager");
        assertThatThrownBy(() -> service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.REMOVE, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRoleAlreadyHeldIsNotGivenAgain() {
        signInAs("tunde", Role.MANAGER, "h1");
        assertThatThrownBy(() -> service.grantRole("victor", new GrantRoleRequest(Role.FRONT_OFFICE, AccessLevel.VIEW, "h1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Victor Adeyemi already has Front office here (Full). Change the level instead");
    }

    @Test
    void theOnlyActiveRoleIsNotRemoved() {
        signInAs("tunde", Role.MANAGER, "h1");
        assertThatThrownBy(() -> service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.REMOVE, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Front office is Victor Adeyemi's only active role. Give them another role first, or pause this one instead");
        verify(grants, never()).delete(any());
    }

    @Test
    void theOnlyRoleIsRemovedWhenThePersonIsLeaving() {
        signInAs("tunde", Role.MANAGER, "h1");
        service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.REMOVE, null, "Left", null, true));
        verify(grants).delete(victorGrants.get(0));
    }

    @Test
    void aSecondRoleIsRemovedAndLogged() {
        signInAs("tunde", Role.MANAGER, "h1");
        victorGrants.add(grant("g-k", Role.KITCHEN, AccessLevel.VIEW, "h1"));
        service.changeAccess("victor", "g-k", new AccessChangeRequest(Change.REMOVE, null, null));
        verify(grants).delete(victorGrants.get(1));
        ArgumentCaptor<AccessChangedEvent> logged = ArgumentCaptor.forClass(AccessChangedEvent.class);
        verify(events).publish(eq(KafkaTopics.ACCESS_CHANGED), logged.capture());
        assertThat(logged.getValue().getChange()).isEqualTo("REMOVE");
        assertThat(logged.getValue().getRole()).isEqualTo("KITCHEN");
    }

    @Test
    void pauseResumeAndLevelChangeOneRole() {
        signInAs("tunde", Role.MANAGER, "h1");
        RoleGrant fo = victorGrants.get(0);
        service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.PAUSE, null, "On leave"));
        assertThat(fo.isPaused()).isTrue();
        assertThatThrownBy(() -> service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.PAUSE, null, null)))
                .hasMessage("Victor Adeyemi's Front office is already paused");
        service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.RESUME, null, null));
        assertThat(fo.isPaused()).isFalse();
        service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.CHANGE_LEVEL, AccessLevel.EDIT, null));
        assertThat(fo.getLevel()).isEqualTo(AccessLevel.EDIT);
        assertThatThrownBy(() -> service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.CHANGE_LEVEL, AccessLevel.EDIT, null)))
                .hasMessage("Front office is already Can edit for Victor Adeyemi");
    }

    @Test
    void setAndClearTheLastDay() {
        signInAs("godwin", Role.OWNER);
        RoleGrant fo = victorGrants.get(0);
        LocalDate last = LocalDate.now(AccessDates.LAGOS).plusDays(7);
        service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.SET_UNTIL, null, null, last, null));
        assertThat(AccessDates.lastDay(fo.getExpiresAt())).isEqualTo(last);
        service.changeAccess("victor", "g-fo", new AccessChangeRequest(Change.SET_UNTIL, null, null, null, null));
        assertThat(fo.getExpiresAt()).isNull();
    }

    @Test
    void managersDoNotTouchTheOwnersRoleOrOtherHotels() {
        signInAs("tunde", Role.MANAGER, "h1");
        victorGrants.add(grant("g-own", Role.OWNER, AccessLevel.FULL, null));
        victorGrants.add(grant("g-h2", Role.BAR, AccessLevel.FULL, "h2"));
        assertThatThrownBy(() -> service.changeAccess("victor", "g-own", new AccessChangeRequest(Change.PAUSE, null, null)))
                .hasMessage("Only Hospio360 can change the owner's access");
        assertThatThrownBy(() -> service.changeAccess("victor", "g-h2", new AccessChangeRequest(Change.PAUSE, null, null)))
                .hasMessage("That role is at a hotel you don't manage");
    }

    @Test
    void expiredTemporaryRolesAreEndedLoggedAndTold() {
        RoleGrant temp = grant("g-t", Role.BAR, AccessLevel.EDIT, "h1");
        temp.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(grants.findByExpiresAtBefore(any())).thenReturn(List.of(temp));

        assertThat(service.endExpiredGrants()).isEqualTo(1);
        verify(grants).delete(temp);
        ArgumentCaptor<AccessChangedEvent> logged = ArgumentCaptor.forClass(AccessChangedEvent.class);
        verify(events).publish(eq(KafkaTopics.ACCESS_CHANGED), logged.capture());
        assertThat(logged.getValue().getChange()).isEqualTo("EXPIRED");
        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        assertThat(notice.getValue().getTitle()).isEqualTo("Your Bar access has ended");
    }
}
