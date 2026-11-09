package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.kafka.events.RoleGrantedEvent;
import com.hospio.common.kafka.events.UserCreatedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AccessChangeRequest;
import com.hospio.identity.dto.CreateUserRequest;
import com.hospio.identity.dto.GrantRoleRequest;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.UserContactResponse;
import com.hospio.identity.dto.UserCreatedResponse;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * People and their roles. Hotels can't create roles; they give the module roles to people at three
 * levels. Who may create whom: the platform anyone (an OWNER when it names the business; a platform role
 * only a SUPER_ADMIN; a MARKETER any platform admin), the owner every hotel role in their business, a
 * manager every hotel role at their own hotels. Every created login gets a temporary password and an
 * invite link, both returned once, and the messages that would carry them come back as {@code deliveries}.
 * Platform accounts (business null, a platform role) are managed by SUPER_ADMIN only.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisher events;
    private final InviteService invites;
    private final TenantClient tenantClient;
    private final DeliveryComposer deliveries;
    private final DeliveryService deliveryService;

    @Value("${hospio.auth.temporary-password-hours:72}")
    private long temporaryPasswordHours;

    @Transactional(readOnly = true)
    public MeResponse me(String userId) {
        // The caller's own row: a marketer (no business, scope marketer) would otherwise be hidden by the row-level policy.
        UserAccount user = TenantContext.runWith(Tenant.system("me"), () -> find(userId));
        List<RoleGrant> active = TenantContext.runWith(Tenant.system("me"), () -> grants.findByUserId(userId)).stream().filter(RoleGrant::isActive).toList();
        // The hotels a person can pick: their grant hotels plus, for an owner, every hotel of the business, which
        // the token already carries (TokenIssuer asks tenant-service); the grants alone would leave an owner with none.
        java.util.LinkedHashSet<String> hotelIds = new java.util.LinkedHashSet<>();
        active.stream().map(RoleGrant::getHotelId).filter(h -> h != null).forEach(hotelIds::add);
        CurrentUser.get().filter(c -> userId.equals(c.getUserId())).ifPresent(c -> hotelIds.addAll(c.getHotelIds()));
        if (hotelIds.isEmpty() && user.getBusinessId() != null && active.stream().anyMatch(g -> g.getRole() == Role.OWNER)) {
            tenantClient.hotelIds(user.getBusinessId()).ifPresent(hotelIds::addAll);
        }
        return new MeResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(),
                active.stream().map(g -> g.getRole().name()).distinct().toList(),
                active.stream().map(g -> new MeResponse.RoleLevel(g.getRole().name(), g.getLevel().name(), g.getHotelId())).toList(),
                user.isPhoneVerified(), !user.isMustChangePassword(), user.isTwoStepEnabled(),
                List.copyOf(hotelIds),
                user.getCreatedAt());
    }

    /**
     * The Users / Staff screens. Platform callers name the business; owners and managers get their own,
     * managers only the people with a grant at one of their hotels. Business-wide grants (the owner)
     * count for every hotel.
     */
    @Transactional(readOnly = true)
    public List<UserResponse> list(String businessId, String hotelId, Role role, String q) {
        HospioUserDetails caller = CurrentUser.require();
        String business;
        if (caller.isPlatform()) {
            if (!StringUtils.hasText(businessId)) {
                throw new BadRequestException("Say which business to list (businessId)");
            }
            business = businessId;
        } else {
            business = TenantContext.requireBusiness();
        }
        boolean managerOnly = !caller.isPlatform() && !caller.has(Role.OWNER);
        Set<String> callerHotels = Set.copyOf(caller.getHotelIds());
        Map<String, List<RoleGrant>> byUser = grants.findByBusinessId(business).stream().collect(Collectors.groupingBy(RoleGrant::getUserId));
        String needle = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;

        List<UserAccount> matched = users.findByBusinessIdOrderByFullName(business).stream()
                .filter(u -> {
                    List<RoleGrant> gs = byUser.getOrDefault(u.getId(), List.of());
                    if (managerOnly && gs.stream().noneMatch(g -> g.getHotelId() != null && callerHotels.contains(g.getHotelId()))) {
                        return false;
                    }
                    if (StringUtils.hasText(hotelId) && gs.stream().noneMatch(g -> g.getHotelId() == null || hotelId.equals(g.getHotelId()))) {
                        return false;
                    }
                    if (role != null && gs.stream().noneMatch(g -> g.getRole() == role)) {
                        return false;
                    }
                    return needle == null || contains(u.getFullName(), needle) || contains(u.getEmail(), needle) || contains(u.getPhone(), needle);
                })
                .toList();
        Map<String, UserResponse.InviteStatus> inviteStatus = invites.statusFor(matched.stream().map(UserAccount::getId).toList());
        return matched.stream().map(u -> toResponse(u, byUser.getOrDefault(u.getId(), List.of()), inviteStatus.get(u.getId()))).toList();
    }

    /** Admin team: every platform account (no business, a platform role). Any platform admin may read it (assignee pickers); changes stay SUPER_ADMIN only. */
    @Transactional(readOnly = true)
    public List<UserResponse> listPlatform(String q) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform()) {
            throw new ForbiddenException("Only H.M.S staff can see the admin team");
        }
        String needle = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;
        List<UserAccount> accounts = users.findByBusinessIdIsNullOrderByFullName();
        Map<String, List<RoleGrant>> byUser = grants.findByUserIdIn(accounts.stream().map(UserAccount::getId).toList()).stream()
                .collect(Collectors.groupingBy(RoleGrant::getUserId));
        List<UserAccount> matched = accounts.stream()
                .filter(u -> byUser.getOrDefault(u.getId(), List.of()).stream().anyMatch(g -> g.getRole().isPlatform() && g.getRole() != Role.SYSTEM))
                .filter(u -> needle == null || contains(u.getFullName(), needle) || contains(u.getEmail(), needle) || contains(u.getPhone(), needle))
                .toList();
        Map<String, UserResponse.InviteStatus> inviteStatus = invites.statusFor(matched.stream().map(UserAccount::getId).toList());
        return matched.stream().map(u -> toResponse(u, byUser.getOrDefault(u.getId(), List.of()), inviteStatus.get(u.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(String userId) {
        return toResponse(find(userId));
    }

    @Transactional
    public UserCreatedResponse create(CreateUserRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        Role role = request.role();
        boolean noBusiness = role.isPlatform() || role == Role.MARKETER;
        String businessId = noBusiness ? null : resolveBusiness(caller, request.businessId());
        assertMayCreate(caller, role);
        String hotelId = noBusiness || role == Role.OWNER ? null : resolveHotel(caller, businessId, request.hotelId());
        String email = request.email().toLowerCase();
        assertEmailFree(email);

        String temporary = Hashing.temporaryPassword();
        Instant expires = Instant.now().plus(temporaryPasswordHours, ChronoUnit.HOURS);
        UserAccount user = users.save(UserAccount.builder()
                .businessId(businessId)
                .fullName(request.fullName().trim())
                .email(email)
                .phone(request.phone())
                .passwordHash(passwordEncoder.encode(temporary))
                .mustChangePassword(true)
                .temporaryPasswordExpiresAt(expires)
                .language(request.language())
                .build());
        grant(user, role, request.level() == null ? AccessLevel.FULL : request.level(), hotelId, null, caller.getUserId());
        InviteService.Issued invite = invites.issue(user, caller.getUserId());
        events.publish(KafkaTopics.USER_CREATED, UserCreatedEvent.builder()
                .businessId(user.getBusinessId()).hotelId(hotelId).userId(user.getId()).fullName(user.getFullName()).email(user.getEmail()).build());
        List<Delivery> sent = deliveryService.send(deliveries.forLogin(user, Set.of(role), temporary, expires, invite.token(), invite.expiresAt()));
        return new UserCreatedResponse(user.getId(), temporary, expires, invite.token(), invite.expiresAt(), sent);
    }

    /** A fresh temporary password and invite link for someone who lost or never got theirs. */
    @Transactional
    public UserCreatedResponse resendInvite(String userId) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can resend an invite");
        }
        UserAccount user = find(userId);
        assertCanManage(caller, user);
        String temporary = Hashing.temporaryPassword();
        Instant expires = Instant.now().plus(temporaryPasswordHours, ChronoUnit.HOURS);
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setMustChangePassword(true);
        user.setTemporaryPasswordExpiresAt(expires);
        users.save(user);
        InviteService.Issued invite = invites.issue(user, caller.getUserId());
        List<Delivery> sent = deliveryService.send(deliveries.forLogin(user, rolesOf(user), temporary, expires, invite.token(), invite.expiresAt()));
        return new UserCreatedResponse(user.getId(), temporary, expires, invite.token(), invite.expiresAt(), sent);
    }

    @Transactional
    public UserResponse grantRole(String userId, GrantRoleRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can change access");
        }
        if (request.role() == Role.OWNER && !caller.isPlatform()) {
            throw new ForbiddenException("The owner role is given by H.M.S or by transferring ownership");
        }
        if ((request.role().isPlatform() || request.role() == Role.MARKETER) && !caller.isPlatform()) {
            throw new ForbiddenException("Only H.M.S can give admin roles");
        }
        if (request.role().isPlatform() && !caller.has(Role.SUPER_ADMIN) && !caller.has(Role.SYSTEM)) {
            throw new ForbiddenException("Only a super admin can give a platform role");
        }
        UserAccount user = find(userId);
        assertCanManage(caller, user);
        grant(user, request.role(), request.level() == null ? AccessLevel.FULL : request.level(), request.hotelId(),
                request.expiresAt(), caller.getUserId());
        return toResponse(user);
    }

    @Transactional
    public UserResponse changeAccess(String userId, String grantId, AccessChangeRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can change access");
        }
        UserAccount user = find(userId);
        assertCanManage(caller, user);
        RoleGrant grant = grants.findById(grantId).filter(g -> g.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
        switch (request.change()) {
            case CHANGE_LEVEL -> {
                if (request.level() == null) {
                    throw new BadRequestException("Choose a level");
                }
                grant.setLevel(request.level());
                grants.save(grant);
            }
            case PAUSE -> {
                grant.setPaused(true);
                grants.save(grant);
            }
            case RESUME -> {
                grant.setPaused(false);
                grants.save(grant);
            }
            case REMOVE -> grants.delete(grant);
        }
        events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder()
                .businessId(user.getBusinessId()).hotelId(grant.getHotelId())
                .userId(userId).userName(user.getFullName()).change(request.change().name()).role(grant.getRole().name()).build());
        return toResponse(user);
    }

    @Transactional
    public UserResponse pauseAll(String userId, boolean paused) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can pause access");
        }
        UserAccount user = find(userId);
        assertCanManage(caller, user);
        if (paused && user.getId().equals(caller.getUserId())) {
            throw new BadRequestException("You cannot pause your own access");
        }
        user.setStatus(paused ? UserAccount.Status.PAUSED : UserAccount.Status.ACTIVE);
        users.save(user);
        events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder()
                .businessId(user.getBusinessId()).userId(userId).userName(user.getFullName())
                .change(paused ? "PAUSED_ALL" : "RESUMED_ALL").build());
        return toResponse(user);
    }

    @Transactional
    public UserCreatedResponse resetPassword(String userId) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can reset a password");
        }
        UserAccount user = find(userId);
        assertCanManage(caller, user);
        String temporary = Hashing.temporaryPassword();
        Instant expires = Instant.now().plus(temporaryPasswordHours, ChronoUnit.HOURS);
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setMustChangePassword(true);
        user.setTemporaryPasswordExpiresAt(expires);
        users.save(user);
        List<Delivery> sent = deliveryService.send(deliveries.forPasswordReset(user, rolesOf(user), temporary, expires));
        return new UserCreatedResponse(user.getId(), temporary, expires, null, null, sent);
    }

    @Transactional(readOnly = true)
    public UserContactResponse contact(String userId) {
        UserAccount user = find(userId);
        return new UserContactResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(), grants.findByUserId(userId).stream().filter(RoleGrant::isActive).map(g -> g.getRole().name()).distinct().toList());
    }

    @Transactional(readOnly = true)
    public List<UserContactResponse> contacts(List<String> userIds) {
        return users.findAllById(userIds).stream().map(u -> contact(u.getId())).toList();
    }

    @Transactional(readOnly = true)
    public List<UserContactResponse> withRole(String hotelId, Role role) {
        return grants.findByHotelIdAndRole(hotelId, role).stream().filter(RoleGrant::isActive)
                .map(g -> contact(g.getUserId())).toList();
    }

    // ---- who may do what ---------------------------------------------------------------------------

    /** Platform callers name the business; everyone else works in their own. */
    private static String resolveBusiness(HospioUserDetails caller, String requested) {
        if (caller.isPlatform()) {
            if (!StringUtils.hasText(requested)) {
                throw new BadRequestException("Say which business this person belongs to (businessId)");
            }
            return requested;
        }
        return TenantContext.requireBusiness();
    }

    /** Platform roles (SUPER_ADMIN, SUPPORT, FINANCE_ADMIN) are created by a SUPER_ADMIN (or the system); marketers by any platform admin. */
    private static void assertMayCreate(HospioUserDetails caller, Role role) {
        if (caller.isPlatform()) {
            if (role == Role.SYSTEM) {
                throw new BadRequestException("SYSTEM is not a role a person can hold");
            }
            if (role.isPlatform() && !caller.has(Role.SUPER_ADMIN) && !caller.has(Role.SYSTEM)) {
                throw new ForbiddenException("Only a super admin can add platform staff");
            }
            return;
        }
        if (role == Role.OWNER) {
            throw new ForbiddenException("Only H.M.S can create an owner; use Transfer ownership instead");
        }
        if (role.isPlatform() || role == Role.MARKETER) {
            throw new ForbiddenException("Only H.M.S can add admin accounts");
        }
        if (!caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can add people");
        }
    }

    /**
     * A hotel role needs a hotel of the business: from the request or the X-Hotel-Id in context. Managers
     * may only use hotels their token grants; owners and the platform may use any hotel of the business,
     * confirmed with tenant-service when the token does not already carry it.
     */
    private String resolveHotel(HospioUserDetails caller, String businessId, String requested) {
        String hotelId = StringUtils.hasText(requested) ? requested : TenantContext.current().map(Tenant::hotelId).orElse(null);
        if (!StringUtils.hasText(hotelId)) {
            throw new BadRequestException("Choose the hotel this role is for");
        }
        if (caller.getHotelIds().contains(hotelId)) {
            return hotelId;
        }
        if (!caller.isPlatform() && !caller.has(Role.OWNER)) {
            throw new ForbiddenException("You can only add people to your own hotels");
        }
        List<String> hotels = tenantClient.hotelIds(businessId)
                .orElseThrow(() -> new ExternalServiceException("Could not confirm the hotel with tenant-service right now"));
        if (!hotels.contains(hotelId)) {
            throw new BadRequestException("That hotel does not belong to this business");
        }
        return hotelId;
    }

    /** An email is unique across every business, so the check runs as the system, outside the caller's row-level scope. */
    private void assertEmailFree(String email) {
        boolean taken = TenantContext.runWith(Tenant.system("email-check"), () -> users.findByEmailIgnoreCase(email).isPresent());
        if (taken) {
            throw new ConflictException("Someone already signs in with " + email);
        }
    }

    /**
     * A manager may only act on people with a grant at one of their hotels; owners on their business; the
     * platform on anyone, except that a platform account (business null, a platform role) is managed by a
     * SUPER_ADMIN only. Marketer accounts are also business-less but any platform admin may manage them.
     */
    private void assertCanManage(HospioUserDetails caller, UserAccount user) {
        if (caller.isPlatform()) {
            if (isPlatformStaff(user) && !caller.has(Role.SUPER_ADMIN) && !caller.has(Role.SYSTEM)) {
                throw new ForbiddenException("Only a super admin can manage admin team accounts");
            }
            return;
        }
        if (user.isPlatformAccount() || caller.getBusinessId() == null || !caller.getBusinessId().equals(user.getBusinessId())) {
            throw new ForbiddenException("That person is not in your business");
        }
        if (!caller.has(Role.OWNER)) {
            Set<String> mine = Set.copyOf(caller.getHotelIds());
            boolean atMyHotel = grants.findByUserId(user.getId()).stream().anyMatch(g -> g.getHotelId() != null && mine.contains(g.getHotelId()));
            if (!atMyHotel) {
                throw new ForbiddenException("That person does not work at one of your hotels");
            }
        }
    }

    private void grant(UserAccount user, Role role, AccessLevel level, String hotelId, Instant expiresAt, String grantedBy) {
        boolean businessWide = role == Role.OWNER || role.isPlatform() || role == Role.MARKETER;
        String hotel = businessWide ? null : hotelId;
        if (hotel == null && !businessWide) {
            hotel = TenantContext.current().map(Tenant::hotelId).orElse(null);
        }
        if (hotel == null && !businessWide) {
            throw new BadRequestException("Choose the hotel this role is for");
        }
        RoleGrant existing = grants.findByUserIdAndHotelIdAndRole(user.getId(), hotel, role).orElse(null);
        RoleGrant grant = existing == null ? new RoleGrant() : existing;
        grant.setUserId(user.getId());
        grant.setBusinessId(user.getBusinessId());
        grant.setHotelId(hotel);
        grant.setRole(role);
        grant.setLevel(level);
        grant.setPaused(false);
        grant.setExpiresAt(expiresAt);
        grant.setGrantedBy(grantedBy);
        grants.save(grant);
        events.publish(KafkaTopics.ROLE_GRANTED, RoleGrantedEvent.builder()
                .businessId(user.getBusinessId()).hotelId(hotel)
                .userId(user.getId()).userName(user.getFullName()).role(role.name()).level(level.name()).build());
    }

    private boolean isPlatformStaff(UserAccount user) {
        return user.isPlatformAccount() && grants.findByUserId(user.getId()).stream().anyMatch(g -> g.getRole().isPlatform());
    }

    private Set<Role> rolesOf(UserAccount user) {
        return grants.findByUserId(user.getId()).stream().map(RoleGrant::getRole).collect(Collectors.toSet());
    }

    private UserAccount find(String userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private UserResponse toResponse(UserAccount user) {
        return toResponse(user, grants.findByUserId(user.getId()), invites.statusFor(List.of(user.getId())).get(user.getId()));
    }

    private static UserResponse toResponse(UserAccount user, List<RoleGrant> userGrants, UserResponse.InviteStatus inviteStatus) {
        List<UserResponse.Grant> gs = userGrants.stream()
                .map(g -> new UserResponse.Grant(g.getId(), g.getRole(), g.getLevel(), g.getHotelId(), g.isPaused(), g.getExpiresAt()))
                .toList();
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(), user.isMustChangePassword(), user.isTwoStepEnabled(), user.getPinHash() != null,
                user.isPhoneVerified(), user.getLanguage(), user.getLastSignInAt(), user.getCreatedAt(), gs,
                inviteStatus == null ? UserResponse.InviteStatus.NONE : inviteStatus);
    }
}
