package com.hospio.identity.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.StaffAccountRequests;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * A staff member's account as the owner (every hotel of the business) or a manager (their own hotels) runs it:
 * their details, a suspension until someone reinstates them, and a pause for a number of days that ends by
 * itself. Suspending or pausing signs the person out everywhere (refresh tokens revoked, live tokens refused
 * through the access-changed topic) and tells the managers of their hotels. Nobody does this to themselves or to
 * the owner; only the owner does it to a manager.
 */
@Service
@RequiredArgsConstructor
public class StaffAccountService {

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final RefreshTokenRepository refreshTokens;
    private final EventPublisher events;
    private final UserService userService;
    private final SharedDeviceService devices;

    @Transactional
    public UserResponse updateDetails(String userId, StaffAccountRequests.Details request) {
        HospioUserDetails caller = CurrentUser.require();
        UserAccount user = target(caller, userId, "edit");
        boolean changed = false;
        if (request.fullName() != null && !request.fullName().trim().equals(user.getFullName())) {
            if (!Phones.meaningfulName(request.fullName())) {
                throw new BadRequestException("Full name must be a real name, with letters");
            }
            user.setFullName(request.fullName().trim());
            changed = true;
        }
        if (request.phone() != null) {
            String phone = Phones.clean(request.phone(), "Phone");
            String before = user.getPhone() == null ? null : Phones.key(user.getPhone());
            String after = phone == null ? null : Phones.key(phone);
            if (!Objects.equals(before, after)) {
                if (phone != null && TenantContext.runWith(Tenant.system("phone-check"), () -> users.countByPhoneKey(after)) > 0) {
                    throw new ConflictException("This phone number is already in use on another account");
                }
                user.setPhone(phone);
                changed = true;
            }
        }
        if (request.email() != null && !request.email().isBlank()) {
            String email = request.email().trim().toLowerCase(Locale.ROOT);
            if (!email.equals(user.getEmail())) {
                boolean taken = TenantContext.runWith(Tenant.system("email-check"), () -> users.findByEmailIgnoreCase(email).isPresent());
                if (taken) {
                    throw new ConflictException("Someone already signs in with " + email);
                }
                user.setEmail(email);
                changed = true;
            }
        }
        if (request.jobTitle() != null) {
            String title = StringUtils.hasText(request.jobTitle()) ? request.jobTitle().trim() : null;
            if (!Objects.equals(title, user.getJobTitle())) {
                user.setJobTitle(title);
                changed = true;
            }
        }
        if (!changed) {
            throw new BadRequestException("Nothing to change: the details are the same as before");
        }
        users.save(user);
        publish(user, "DETAILS_CHANGED", null, caller.getFullName());
        notice(List.of(user.getId()), null, null, "Your details were updated", "By " + caller.getFullName() + ". Check them on your profile");
        return userService.get(user.getId());
    }

    @Transactional
    public UserResponse suspend(String userId, StaffAccountRequests.Suspend request) {
        HospioUserDetails caller = CurrentUser.require();
        UserAccount user = target(caller, userId, "suspend");
        String reason = request == null ? null : clean(request.reason());
        if (reason == null || reason.length() < 3) {
            throw new BadRequestException("Say why " + user.getFullName() + " is suspended");
        }
        if (user.getStatus() == UserAccount.Status.SUSPENDED) {
            throw new BadRequestException(user.getFullName() + " is already suspended");
        }
        user.setStatus(UserAccount.Status.SUSPENDED);
        user.setPausedUntil(null);
        hold(user, reason, caller);
        users.save(user);
        signOutEverywhere(user.getId());
        publish(user, "SUSPENDED", reason, caller.getFullName());
        tellManagers(user, caller, user.getFullName() + " was suspended", "By " + caller.getFullName() + ": " + reason);
        return userService.get(user.getId());
    }

    @Transactional
    public UserResponse pause(String userId, StaffAccountRequests.Pause request) {
        HospioUserDetails caller = CurrentUser.require();
        UserAccount user = target(caller, userId, "pause");
        if (user.getStatus() == UserAccount.Status.SUSPENDED) {
            throw new BadRequestException(user.getFullName() + " is suspended. Reinstate them first");
        }
        if (user.getStatus() == UserAccount.Status.PAUSED && !user.pauseHasEnded(Instant.now())) {
            throw new BadRequestException(user.getFullName() + " is already paused"
                    + (user.getPausedUntil() == null ? "" : " until " + AccessDates.day(AccessDates.lastDay(user.getPausedUntil()))));
        }
        int days = request.days();
        LocalDate lastDay = LocalDate.now(AccessDates.LAGOS).plusDays(days - 1L);
        user.setStatus(UserAccount.Status.PAUSED);
        user.setPausedUntil(AccessDates.endOf(lastDay));
        hold(user, clean(request.reason()), caller);
        users.save(user);
        signOutEverywhere(user.getId());
        publish(user, "PAUSED_UNTIL", lastDay.toString(), caller.getFullName());
        tellManagers(user, caller, user.getFullName() + " is paused until " + AccessDates.day(lastDay),
                "By " + caller.getFullName() + (user.getStatusReason() == null ? "" : ": " + user.getStatusReason()));
        return userService.get(user.getId());
    }

    @Transactional
    public UserResponse reinstate(String userId) {
        HospioUserDetails caller = CurrentUser.require();
        UserAccount user = target(caller, userId, "reinstate");
        if (user.getStatus() == UserAccount.Status.ACTIVE || user.pauseHasEnded(Instant.now())) {
            throw new BadRequestException(user.getFullName() + " is not suspended or paused");
        }
        markActive(user);
        users.save(user);
        publish(user, "REINSTATED", null, caller.getFullName());
        notice(List.of(user.getId()), null, null, "You can sign in again", "Reinstated by " + caller.getFullName());
        tellManagers(user, caller, user.getFullName() + " can sign in again", "Reinstated by " + caller.getFullName());
        return userService.get(user.getId());
    }

    /**
     * A manager clears a person's PIN (forgotten, or seen by someone): it stops working on every shared device and its wrong-try
     * lock is lifted. The person sets a new one from their profile after a normal sign-in.
     */
    @Transactional
    public UserResponse resetPin(String userId) {
        HospioUserDetails caller = CurrentUser.require();
        UserAccount user = target(caller, userId, "reset the PIN of");
        if (user.getPinHash() == null && user.getLockedUntil() == null) {
            throw new BadRequestException(user.getFullName() + " has no PIN to reset");
        }
        user.setPinHash(null);
        user.setFailedPinAttempts(0);
        user.setLockedUntil(null);
        users.save(user);
        devices.audit(null, "PIN_RESET", user, null);
        notice(List.of(user.getId()), user.getBusinessId(), null, "Your PIN was reset",
                "By " + caller.getFullName() + ". Sign in with email and set a new PIN from your profile");
        return userService.get(user.getId());
    }

    /** A manager nudges someone with no PIN yet: an in-app notice that opens their profile's security tab. */
    @Transactional(readOnly = true)
    public void remindPin(String userId) {
        HospioUserDetails caller = CurrentUser.require();
        UserAccount user = target(caller, userId, "remind");
        if (user.getPinHash() != null) {
            throw new BadRequestException(user.getFullName() + " already has a PIN");
        }
        notice(List.of(user.getId()), user.getBusinessId(), null, "Set a PIN for the shared devices",
                caller.getFullName() + " asks you to set a PIN so you can switch in quickly on the hotel's shared tablets. "
                        + "Open My profile & security, Security");
    }

    /**
     * Timed pauses whose last day has passed: active again, logged and the person told. Runs as the system. Each account is
     * resumed with a guarded update, so one resumed meanwhile (another run, a manager) is neither changed nor told twice.
     */
    @Transactional
    public int resumeDuePauses() {
        return TenantContext.runWith(Tenant.system("pause-expiry"), () -> {
            Instant now = Instant.now();
            List<UserAccount> due = users.findByStatusAndPausedUntilLessThanEqual(UserAccount.Status.PAUSED, now);
            int resumed = 0;
            for (UserAccount user : due) {
                if (users.resumeIfDue(user.getId(), now, UserAccount.Status.PAUSED, UserAccount.Status.ACTIVE) != 1) {
                    continue;
                }
                markActive(user); // the loaded copy is detached by the update; this only keeps it in step for the events
                publish(user, "RESUMED", null, "Hospio");
                notice(List.of(user.getId()), user.getBusinessId(), null, "Your pause has ended", "You can sign in and work again");
                resumed++;
            }
            return resumed;
        });
    }

    /** Back to active: the pause end, the reason and who held it are cleared. */
    static void markActive(UserAccount user) {
        user.setStatus(UserAccount.Status.ACTIVE);
        user.setPausedUntil(null);
        user.setStatusReason(null);
        user.setStatusChangedBy(null);
        user.setStatusChangedAt(Instant.now());
    }

    // ---- who may act on whom ---------------------------------------------------------------------

    /**
     * The person, if the caller may act on them: the platform on any business account, the owner on anyone in
     * their business but themselves, a manager on the people of their own hotels except managers and the owner.
     */
    UserAccount target(HospioUserDetails caller, String userId, String verb) {
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can " + verb + " a staff member");
        }
        if (userId.equals(caller.getUserId())) {
            throw new ForbiddenException("You can't " + verb + " your own account here. Use your profile, or ask "
                    + (caller.has(Role.OWNER) ? "Hospio360 support" : "the owner"));
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("That person was not found"));
        if (caller.isPlatform()) {
            if (user.isPlatformAccount()) {
                throw new ForbiddenException("Admin team accounts are managed from the admin portal");
            }
            return user;
        }
        if (user.isPlatformAccount() || caller.getBusinessId() == null || !caller.getBusinessId().equals(user.getBusinessId())) {
            throw new ForbiddenException("That person is not in your business");
        }
        List<RoleGrant> theirs = grants.findByUserId(user.getId());
        if (theirs.stream().anyMatch(g -> g.getRole() == Role.OWNER)) {
            throw new ForbiddenException("Only Hospio360 can change the owner's account");
        }
        if (!caller.has(Role.OWNER)) {
            Set<String> mine = Set.copyOf(caller.getHotelIds());
            if (theirs.stream().noneMatch(g -> g.getHotelId() != null && mine.contains(g.getHotelId()))) {
                throw new ForbiddenException("That person does not work at one of your hotels");
            }
            if (theirs.stream().anyMatch(g -> g.getRole() == Role.MANAGER)) {
                throw new ForbiddenException("Only the owner can " + verb + " a manager");
            }
        }
        return user;
    }

    private static void hold(UserAccount user, String reason, HospioUserDetails caller) {
        user.setStatusReason(reason);
        user.setStatusChangedBy(caller.getFullName());
        user.setStatusChangedAt(Instant.now());
    }

    private void signOutEverywhere(String userId) {
        TenantContext.runWith(Tenant.system("sign-out"), () -> {
            Instant now = Instant.now();
            refreshTokens.findByUserIdAndRevokedAtIsNull(userId).forEach(t -> {
                t.setRevokedAt(now);
                refreshTokens.save(t);
            });
        });
    }

    private void publish(UserAccount user, String change, String detail, String actorName) {
        events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder()
                .businessId(user.getBusinessId()).actorName(actorName)
                .userId(user.getId()).userName(user.getFullName()).change(change).detail(detail).build());
    }

    /** The managers of every hotel the person works at (not the caller). */
    private void tellManagers(UserAccount user, HospioUserDetails caller, String title, String body) {
        grants.findByUserId(user.getId()).stream().map(RoleGrant::getHotelId).filter(Objects::nonNull).distinct().forEach(hotelId ->
                events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("STAFF").title(title).body(body).link("/staff")
                        .businessId(user.getBusinessId()).hotelId(hotelId).roles(List.of(Role.MANAGER.name())).build()));
    }

    private void notice(List<String> userIds, String businessId, String hotelId, String title, String body) {
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("ACCOUNT").title(title).body(body)
                .link(title.contains("PIN") ? "/profile?tab=security" : "/profile")
                .businessId(businessId).hotelId(hotelId).userIds(userIds).build());
    }

    private static String clean(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
