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
import com.hospio.common.kafka.events.NoticeEvent;
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
                user.getCreatedAt(), user.getPinHash() != null,
                user.getLanguage(), user.getPhotoVersion(), user.getPasswordChangedAt(), user.getJobTitle());
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

    /**
     * The people working at the caller's active hotel, for any signed-in staff member (swap and cover pickers):
     * names and this hotel's roles only; contact details and account state stay with managers.
     */
    @Transactional(readOnly = true)
    public List<UserResponse> colleagues() {
        CurrentUser.require();
        String business = TenantContext.requireBusiness();
        String hotel = TenantContext.requireHotel();
        Map<String, List<RoleGrant>> byUser = grants.findByBusinessId(business).stream()
                .filter(g -> !g.isPaused() && (g.getHotelId() == null || hotel.equals(g.getHotelId())))
                .collect(Collectors.groupingBy(RoleGrant::getUserId));
        return users.findByBusinessIdOrderByFullName(business).stream()
                .filter(u -> u.getStatus() == UserAccount.Status.ACTIVE && byUser.containsKey(u.getId()))
                .map(u -> {
                    UserResponse full = toResponse(u, byUser.get(u.getId()), UserResponse.InviteStatus.NONE);
                    return new UserResponse(full.id(), full.fullName(), "", null, full.businessId(), full.status(), false, false, false, false,
                            null, null, full.createdAt(), full.grants(), UserResponse.InviteStatus.NONE);
                })
                .toList();
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
        String email = request.email().trim().toLowerCase();
        if (!Phones.meaningfulName(request.fullName())) {
            throw new BadRequestException("Full name must be a real name, with letters");
        }
        String phone = Phones.clean(request.phone(), "Phone");
        assertEmailFree(email);
        assertPhoneFree(phone);

        String temporary = Hashing.temporaryPassword();
        Instant expires = Instant.now().plus(temporaryPasswordHours, ChronoUnit.HOURS);
        UserAccount user = users.save(UserAccount.builder()
                .businessId(businessId)
                .fullName(request.fullName().trim())
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(temporary))
                .mustChangePassword(true)
                .temporaryPasswordExpiresAt(expires)
                .language(request.language())
                .build());
        grant(user, role, request.level() == null ? AccessLevel.FULL : request.level(), hotelId, null, caller.getUserId());
        InviteService.Issued invite = invites.issue(user, caller.getUserId());
        events.publish(KafkaTopics.USER_CREATED, UserCreatedEvent.builder()
                .businessId(user.getBusinessId()).hotelId(hotelId).userId(user.getId()).fullName(user.getFullName()).email(user.getEmail()).build());
        List<Delivery> sent = deliveryService.send(deliveries.forLogin(user, Set.of(role), temporary, expires, invite.token(), invite.expiresAt())
                .stream().map(d -> d.from(businessId, hotelId)).toList());
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
        List<Delivery> sent = deliveryService.send(fromTheirHotel(user, deliveries.forLogin(user, rolesOf(user), temporary, expires, invite.token(), invite.expiresAt())));
        return new UserCreatedResponse(user.getId(), temporary, expires, invite.token(), invite.expiresAt(), sent);
    }

    /**
     * Another role for a person (Roles &amp; permissions · Give a role). A manager or the owner, never on themselves;
     * the owner role and the H.M.S roles are not theirs to give; a manager gives roles at their own hotels only
     * (MANAGER included, as invites allow). A role the person already holds at that hotel is changed, not given
     * again. The person gets an in-app notice and their next request carries the new role (AccessRevocations).
     */
    @Transactional
    public UserResponse grantRole(String userId, GrantRoleRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can change access");
        }
        assertNotSelf(caller, userId);
        if (request.role() == Role.SYSTEM) {
            throw new BadRequestException("SYSTEM is not a role a person can hold");
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
        boolean businessWide = businessWide(request.role());
        String hotelId = businessWide ? null : resolveHotel(caller, user.getBusinessId(), request.hotelId());
        Instant expiresAt = request.until() != null ? AccessDates.endOf(request.until()) : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new BadRequestException("The last day must be today or later");
        }
        AccessLevel level = request.level() == null ? AccessLevel.FULL : request.level();
        RoleGrant existing = businessWide ? null : grants.findByUserIdAndHotelIdAndRole(userId, hotelId, request.role()).orElse(null);
        if (existing != null && (existing.getExpiresAt() == null || existing.getExpiresAt().isAfter(Instant.now()))) {
            String label = AccessDates.roleLabel(request.role());
            throw new ConflictException(existing.isPaused()
                    ? user.getFullName() + " already has " + label + " here, paused. Resume it instead"
                    : user.getFullName() + " already has " + label + " here (" + AccessDates.levelLabel(existing.getLevel())
                            + "). Change the level instead");
        }
        grant(user, request.role(), level, hotelId, expiresAt, caller.getUserId());
        notice(user, hotelId, "You now have " + AccessDates.roleLabel(request.role()) + " · " + AccessDates.levelLabel(level),
                "Given by " + caller.getFullName() + (expiresAt == null ? "" : ", until " + AccessDates.day(AccessDates.lastDay(expiresAt)))
                        + ". Switch to it from your profile menu");
        return toResponse(user);
    }

    /**
     * Change one role: its level, pause or resume it, set or clear its last day, or remove it. A manager or the
     * owner, never on their own roles; a manager only touches roles at their own hotels and never the owner's.
     * A person's only active role is not removed (pause it, or give another first) unless the caller says the
     * person is leaving ({@code allowLastRole}).
     */
    @Transactional
    public UserResponse changeAccess(String userId, String grantId, AccessChangeRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can change access");
        }
        assertNotSelf(caller, userId);
        UserAccount user = find(userId);
        assertCanManage(caller, user);
        RoleGrant grant = grants.findById(grantId).filter(g -> g.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("That role is no longer on " + user.getFullName() + "'s access. Refresh the list"));
        if (!caller.isPlatform()) {
            if (grant.getRole() == Role.OWNER) {
                throw new ForbiddenException("Only H.M.S can change the owner's access");
            }
            if (!caller.has(Role.OWNER) && (grant.getHotelId() == null || !caller.getHotelIds().contains(grant.getHotelId()))) {
                throw new ForbiddenException("That role is at a hotel you don't manage");
            }
        }
        String label = AccessDates.roleLabel(grant.getRole());
        String detail = null;
        String title;
        String body = "Changed by " + caller.getFullName();
        switch (request.change()) {
            case CHANGE_LEVEL -> {
                if (request.level() == null) {
                    throw new BadRequestException("Choose a level");
                }
                if (request.level() == grant.getLevel()) {
                    throw new BadRequestException(label + " is already " + AccessDates.levelLabel(grant.getLevel()) + " for " + user.getFullName());
                }
                grant.setLevel(request.level());
                grants.save(grant);
                detail = request.level().name();
                title = "Your " + label + " access is now " + AccessDates.levelLabel(request.level());
            }
            case PAUSE -> {
                if (grant.isPaused()) {
                    throw new BadRequestException(user.getFullName() + "'s " + label + " is already paused");
                }
                grant.setPaused(true);
                grants.save(grant);
                detail = blankToNull(request.reason());
                title = "Your " + label + " access is paused";
                if (detail != null) {
                    body = detail + " · " + body;
                }
            }
            case RESUME -> {
                if (!grant.isPaused()) {
                    throw new BadRequestException(user.getFullName() + "'s " + label + " is not paused");
                }
                grant.setPaused(false);
                grants.save(grant);
                title = "Your " + label + " access is back";
            }
            case SET_UNTIL -> {
                if (request.until() == null) {
                    if (grant.getExpiresAt() == null) {
                        throw new BadRequestException(user.getFullName() + "'s " + label + " has no end date");
                    }
                    grant.setExpiresAt(null);
                    title = "Your " + label + " access no longer has an end date";
                } else {
                    Instant ends = AccessDates.endOf(request.until());
                    if (!ends.isAfter(Instant.now())) {
                        throw new BadRequestException("The last day must be today or later");
                    }
                    grant.setExpiresAt(ends);
                    detail = request.until().toString();
                    title = "Your " + label + " access ends after " + AccessDates.day(request.until());
                }
                grants.save(grant);
            }
            case REMOVE -> {
                boolean othersActive = grants.findByUserId(userId).stream().anyMatch(g -> !g.getId().equals(grantId) && g.isActive());
                if (grant.isActive() && !othersActive && !Boolean.TRUE.equals(request.allowLastRole())) {
                    throw new BadRequestException(label + " is " + user.getFullName()
                            + "'s only active role. Give them another role first, or pause this one instead");
                }
                grants.delete(grant);
                detail = blankToNull(request.reason());
                title = label + " was removed from your access";
            }
            default -> throw new BadRequestException("Unknown change");
        }
        events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder()
                .businessId(user.getBusinessId()).hotelId(grant.getHotelId())
                .userId(userId).userName(user.getFullName()).change(request.change().name()).role(grant.getRole().name()).detail(detail).build());
        notice(user, grant.getHotelId(), title, body);
        return toResponse(user);
    }

    /**
     * Temporary roles whose last day has passed are ended: deleted, logged as EXPIRED and the person told.
     * Runs as the system across every business (the role grants table is row-level scoped). Returns how many.
     */
    @Transactional
    public int endExpiredGrants() {
        return TenantContext.runWith(Tenant.system("grant-expiry"), () -> {
            List<RoleGrant> ended = grants.findByExpiresAtBefore(Instant.now());
            for (RoleGrant g : ended) {
                UserAccount user = users.findById(g.getUserId()).orElse(null);
                grants.delete(g);
                if (user == null) {
                    continue;
                }
                String label = AccessDates.roleLabel(g.getRole());
                events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder()
                        .businessId(g.getBusinessId()).hotelId(g.getHotelId()).actorName("Hospio")
                        .userId(user.getId()).userName(user.getFullName()).change("EXPIRED").role(g.getRole().name())
                        .detail(AccessDates.lastDay(g.getExpiresAt()).toString()).build());
                notice(user, g.getHotelId(), "Your " + label + " access has ended",
                        "It was given until " + AccessDates.day(AccessDates.lastDay(g.getExpiresAt())) + ". Ask your manager if you still need it");
            }
            return ended.size();
        });
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
        if (paused) {
            user.setStatus(UserAccount.Status.PAUSED);
            user.setPausedUntil(null);
        } else {
            StaffAccountService.markActive(user);
        }
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
        List<Delivery> sent = deliveryService.send(fromTheirHotel(user, deliveries.forPasswordReset(user, rolesOf(user), temporary, expires)));
        return new UserCreatedResponse(user.getId(), temporary, expires, null, null, sent);
    }

    /** Emails to a person come from the hotel they work at (its name is the From name); platform people get the platform's. */
    private List<Delivery> fromTheirHotel(UserAccount user, List<Delivery> out) {
        String hotel = TenantContext.current().map(t -> t.hotelId()).orElse(null);
        if (hotel == null) {
            hotel = grants.findByUserId(user.getId()).stream().filter(RoleGrant::isActive).map(RoleGrant::getHotelId)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }
        String h = hotel;
        return out.stream().map(d -> d.from(user.getBusinessId(), h)).toList();
    }

    @Transactional(readOnly = true)
    public UserContactResponse contact(String userId) {
        UserAccount user = find(userId);
        return new UserContactResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(), grants.findByUserId(userId).stream().filter(RoleGrant::isActive).map(g -> g.getRole().name()).distinct().toList(),
                NotificationPrefs.email(user.getEmailOff()));
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

    /**
     * Whether an email or phone already belongs to a login, across every business (tenant-service asks before it records a new
     * owner, so the handover never fails on a taken sign-in). The phone is matched on its last ten digits, however it was typed.
     */
    public java.util.Map<String, Boolean> taken(String email, String phone) {
        return TenantContext.runWith(Tenant.system("taken-check"), () -> java.util.Map.of(
                "email", email != null && !email.isBlank() && users.findByEmailIgnoreCase(email.trim()).isPresent(),
                "phone", phone != null && Phones.key(phone).length() == 10 && users.countByPhoneKey(Phones.key(phone)) > 0));
    }

    /** An email is unique across every business, so the check runs as the system, outside the caller's row-level scope. */
    private void assertPhoneFree(String phone) {
        if (phone == null) {
            return;
        }
        long taken = TenantContext.runWith(Tenant.system("phone-check"), () -> users.countByPhoneKey(Phones.key(phone)));
        if (taken > 0) {
            throw new ConflictException("This phone number is already in use on another account");
        }
    }

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
        boolean businessWide = businessWide(role);
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

    /** No one changes their own roles: another manager or the owner does it, so every change has a second person. */
    private static void assertNotSelf(HospioUserDetails caller, String userId) {
        if (userId.equals(caller.getUserId())) {
            throw new ForbiddenException("You can't change your own access. Ask the owner or another manager");
        }
    }

    private static boolean businessWide(Role role) {
        return role == Role.OWNER || role.isPlatform() || role == Role.MARKETER;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    /** An in-app notice to the person whose access changed (messaging-service, NOTICE topic). */
    private void notice(UserAccount user, String hotelId, String title, String body) {
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("ACCESS").title(title).body(body).link("/profile")
                .businessId(user.getBusinessId()).hotelId(hotelId).userIds(List.of(user.getId())).build());
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
                .map(g -> new UserResponse.Grant(g.getId(), g.getRole(), g.getLevel(), g.getHotelId(), g.isPaused(), g.getExpiresAt(),
                        g.getExpiresAt() == null ? null : AccessDates.lastDay(g.getExpiresAt()), g.getGrantedBy()))
                .toList();
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(), user.isMustChangePassword(), user.isTwoStepEnabled(), user.getPinHash() != null,
                user.isPhoneVerified(), user.getLanguage(), user.getLastSignInAt(), user.getCreatedAt(), gs,
                inviteStatus == null ? UserResponse.InviteStatus.NONE : inviteStatus).withAccount(user);
    }

    // ---- paged (docs/pagination.md) -----------------------------------------------------------------------

    static final Map<String, String> USER_SORTS = Map.of("fullName", "fullName", "email", "email", "createdAt", "createdAt",
            "lastSignInAt", "lastSignInAt");

    /**
     * The Users / Staff list one page at a time, filtered in the database the same way as {@link #list}: the business (platform callers
     * name it), managers only their hotels' people, then {@code hotelId}, {@code role}, {@code status} (ACTIVE, PAUSED, SUSPENDED; a pause
     * that ran out reads ACTIVE) and {@code q} (name, email or phone). A to Z unless {@code sort}. {@code counts}: all, ACTIVE, PAUSED,
     * SUSPENDED, MANAGER (people with a manager role), INVITE_PENDING and INVITE_EXPIRED, over every filter but the status.
     */
    @Transactional(readOnly = true)
    public com.hospio.common.response.PageWithCounts<UserResponse> page(String businessId, String hotelId, Role role, String status, String q,
                                                                        Integer page, Integer size, String sort) {
        return page(businessId, hotelId, role, status, q, page, size, sort, false, false);
    }

    /** Statuses a page can ask for besides the account's own: HELD (paused or suspended), INVITED (invite not accepted, not held), WORKING (neither). */
    static final Set<String> PAGE_STATUSES = Set.of("ACTIVE", "PAUSED", "SUSPENDED", "HELD", "INVITED", "WORKING");

    /**
     * {@code staffOnly} leaves out the owner (the owner's Staff screen); {@code roleCounts} adds {@code ROLE_<ROLE>}: people holding the role,
     * not paused, at {@code hotelId} or business-wide (Roles & permissions' role cards), over the same search.
     */
    public com.hospio.common.response.PageWithCounts<UserResponse> page(String businessId, String hotelId, Role role, String status, String q,
                                                                        Integer page, Integer size, String sort, boolean staffOnly, boolean roleCounts) {
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
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (st != null && !PAGE_STATUSES.contains(st)) {
            throw new BadRequestException("status is ACTIVE, PAUSED, SUSPENDED, HELD, INVITED or WORKING");
        }
        boolean managerOnly = !caller.isPlatform() && !caller.has(Role.OWNER);
        Set<String> callerHotels = Set.copyOf(caller.getHotelIds());
        org.springframework.data.domain.Sort order = com.hospio.common.paging.Paging.sort(sort, USER_SORTS,
                org.springframework.data.domain.Sort.by("fullName").and(org.springframework.data.domain.Sort.by("id")));
        org.springframework.data.jpa.domain.Specification<UserAccount> staff = staffOnly ? notOwner(business) : null;
        java.util.function.Function<org.springframework.data.jpa.domain.Specification<UserAccount>, org.springframework.data.jpa.domain.Specification<UserAccount>> scoped =
                spec -> staff == null ? spec : spec.and(staff);
        org.springframework.data.domain.Page<UserAccount> result = users.findAll(
                scoped.apply(userSpec(business, managerOnly, callerHotels, hotelId, role, st, q, null)),
                com.hospio.common.paging.Paging.of(page, size, order));
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("all", users.count(scoped.apply(userSpec(business, managerOnly, callerHotels, hotelId, role, null, q, null))));
        for (String s : List.of("ACTIVE", "PAUSED", "SUSPENDED", "HELD", "INVITED", "WORKING")) {
            counts.put(s, users.count(scoped.apply(userSpec(business, managerOnly, callerHotels, hotelId, role, s, q, null))));
        }
        counts.put("MANAGER", users.count(scoped.apply(userSpec(business, managerOnly, callerHotels, hotelId, Role.MANAGER, null, q, null))));
        counts.put("INVITE_PENDING", users.count(scoped.apply(userSpec(business, managerOnly, callerHotels, hotelId, role, null, q, UserResponse.InviteStatus.PENDING))));
        counts.put("INVITE_EXPIRED", users.count(scoped.apply(userSpec(business, managerOnly, callerHotels, hotelId, role, null, q, UserResponse.InviteStatus.EXPIRED))));
        if (roleCounts) {
            for (Role r : Role.values()) {
                counts.put("ROLE_" + r.name(), users.count(scoped.apply(userSpec(business, managerOnly, callerHotels, null, null, null, q, null))
                        .and(holding(business, r, hotelId))));
            }
        }
        List<String> ids = result.getContent().stream().map(UserAccount::getId).toList();
        Map<String, List<RoleGrant>> byUser = ids.isEmpty() ? Map.of()
                : grants.findByUserIdIn(ids).stream().filter(g -> business.equals(g.getBusinessId())).collect(Collectors.groupingBy(RoleGrant::getUserId));
        Map<String, UserResponse.InviteStatus> inviteStatus = invites.statusFor(ids);
        return com.hospio.common.response.PageWithCounts.of(result,
                u -> toResponse(u, byUser.getOrDefault(u.getId(), List.of()), inviteStatus.get(u.getId())), counts, Map.of());
    }

    /** The {@link #list} filters as a query: grants are this business's; a business-wide grant (no hotel) counts for every hotel. */
    static org.springframework.data.jpa.domain.Specification<UserAccount> userSpec(String business, boolean managerOnly, Set<String> callerHotels,
                                                                                   String hotelId, Role role, String status, String q,
                                                                                   UserResponse.InviteStatus invite) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new java.util.ArrayList<>();
            ps.add(cb.equal(root.get("businessId"), business));
            if (managerOnly) {
                ps.add(cb.exists(grant(root, query, cb, business, g -> callerHotels.isEmpty() ? cb.disjunction() : g.get("hotelId").in(callerHotels))));
            }
            if (StringUtils.hasText(hotelId)) {
                ps.add(cb.exists(grant(root, query, cb, business, g -> cb.or(cb.isNull(g.get("hotelId")), cb.equal(g.get("hotelId"), hotelId)))));
            }
            if (role != null) {
                // With a hotel too, the role must be held there (or business-wide), not at another hotel.
                ps.add(cb.exists(grant(root, query, cb, business, g -> StringUtils.hasText(hotelId)
                        ? cb.and(cb.equal(g.get("role"), role), cb.or(cb.isNull(g.get("hotelId")), cb.equal(g.get("hotelId"), hotelId)))
                        : cb.equal(g.get("role"), role))));
            }
            Instant now = Instant.now();
            if (status != null) {
                jakarta.persistence.criteria.Predicate pauseOver = cb.and(cb.equal(root.get("status"), UserAccount.Status.PAUSED),
                        cb.isNotNull(root.get("pausedUntil")), cb.lessThanOrEqualTo(root.get("pausedUntil"), now));
                jakarta.persistence.criteria.Predicate active = cb.or(cb.equal(root.get("status"), UserAccount.Status.ACTIVE), pauseOver);
                switch (status) {
                    case "ACTIVE" -> ps.add(active);
                    case "PAUSED" -> ps.add(cb.and(cb.equal(root.get("status"), UserAccount.Status.PAUSED), cb.not(pauseOver)));
                    case "HELD" -> ps.add(cb.not(active));
                    case "INVITED" -> ps.add(cb.and(active, invitedNotAccepted(root, query, cb)));
                    case "WORKING" -> ps.add(cb.and(active, cb.not(invitedNotAccepted(root, query, cb))));
                    default -> ps.add(cb.equal(root.get("status"), UserAccount.Status.SUSPENDED));
                }
            }
            if (StringUtils.hasText(q)) {
                String like = com.hospio.common.paging.Paging.like(q);
                ps.add(cb.or(cb.like(cb.lower(root.get("fullName")), like, '\\'), cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), like, '\\')));
            }
            if (invite != null) {
                jakarta.persistence.criteria.Subquery<String> accepted = query.subquery(String.class);
                var a = accepted.from(com.hospio.identity.entity.Invite.class);
                accepted.select(a.get("userId")).where(cb.equal(a.get("userId"), root.get("id")), cb.isNotNull(a.get("acceptedAt")));
                jakarta.persistence.criteria.Subquery<String> open = query.subquery(String.class);
                var o = open.from(com.hospio.identity.entity.Invite.class);
                open.select(o.get("userId")).where(cb.equal(o.get("userId"), root.get("id")), cb.isNull(o.get("acceptedAt")),
                        cb.greaterThan(o.get("expiresAt"), now));
                ps.add(cb.not(cb.exists(accepted)));
                if (invite == UserResponse.InviteStatus.PENDING) {
                    ps.add(cb.exists(open));
                } else {
                    jakarta.persistence.criteria.Subquery<String> any = query.subquery(String.class);
                    var x = any.from(com.hospio.identity.entity.Invite.class);
                    any.select(x.get("userId")).where(cb.equal(x.get("userId"), root.get("id")));
                    ps.add(cb.exists(any));
                    ps.add(cb.not(cb.exists(open)));
                }
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /** Has an invite and none accepted: the invite is pending or expired (the screens' "Invited"). */
    private static jakarta.persistence.criteria.Predicate invitedNotAccepted(jakarta.persistence.criteria.Root<UserAccount> root,
                                                                              jakarta.persistence.criteria.CriteriaQuery<?> query,
                                                                              jakarta.persistence.criteria.CriteriaBuilder cb) {
        jakarta.persistence.criteria.Subquery<String> accepted = query.subquery(String.class);
        var a = accepted.from(com.hospio.identity.entity.Invite.class);
        accepted.select(a.get("userId")).where(cb.equal(a.get("userId"), root.get("id")), cb.isNotNull(a.get("acceptedAt")));
        jakarta.persistence.criteria.Subquery<String> any = query.subquery(String.class);
        var x = any.from(com.hospio.identity.entity.Invite.class);
        any.select(x.get("userId")).where(cb.equal(x.get("userId"), root.get("id")));
        return cb.and(cb.exists(any), cb.not(cb.exists(accepted)));
    }

    /** No OWNER grant in the business (the owner's Staff screen lists the people who work for them). */
    static org.springframework.data.jpa.domain.Specification<UserAccount> notOwner(String business) {
        return (root, query, cb) -> cb.not(cb.exists(grant(root, query, cb, business, g -> cb.equal(g.get("role"), Role.OWNER))));
    }

    /** Holds {@code role}, not paused, at {@code hotelId} or business-wide (any hotel when none is given). */
    static org.springframework.data.jpa.domain.Specification<UserAccount> holding(String business, Role role, String hotelId) {
        return (root, query, cb) -> cb.exists(grant(root, query, cb, business, g -> {
            jakarta.persistence.criteria.Predicate p = cb.and(cb.equal(g.get("role"), role), cb.isFalse(g.get("paused")));
            return StringUtils.hasText(hotelId) ? cb.and(p, cb.or(cb.isNull(g.get("hotelId")), cb.equal(g.get("hotelId"), hotelId))) : p;
        }));
    }

    private static jakarta.persistence.criteria.Subquery<String> grant(jakarta.persistence.criteria.Root<UserAccount> user,
                                                                       jakarta.persistence.criteria.CriteriaQuery<?> query,
                                                                       jakarta.persistence.criteria.CriteriaBuilder cb, String business,
                                                                       java.util.function.Function<jakarta.persistence.criteria.Root<RoleGrant>,
                                                                               jakarta.persistence.criteria.Predicate> also) {
        jakarta.persistence.criteria.Subquery<String> sq = query.subquery(String.class);
        jakarta.persistence.criteria.Root<RoleGrant> g = sq.from(RoleGrant.class);
        sq.select(g.get("userId")).where(cb.equal(g.get("userId"), user.get("id")), cb.equal(g.get("businessId"), business), also.apply(g));
        return sq;
    }
}
