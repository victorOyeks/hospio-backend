package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.kafka.events.RoleGrantedEvent;
import com.hospio.common.kafka.events.UserCreatedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People and their roles. Hotels can't create roles; they give the module roles to people at three
 * levels. Changing access is always manager-only, and the owner role is only ever granted by the
 * platform during onboarding or by the owner transferring ownership.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisher events;

    @Value("${hospio.auth.temporary-password-hours:72}")
    private long temporaryPasswordHours;

    @Transactional(readOnly = true)
    public MeResponse me(String userId) {
        UserAccount user = find(userId);
        List<RoleGrant> active = grants.findByUserId(userId).stream().filter(RoleGrant::isActive).toList();
        return new MeResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(),
                active.stream().map(g -> g.getRole().name()).distinct().toList(),
                active.stream().map(g -> new MeResponse.RoleLevel(g.getRole().name(), g.getLevel().name(), g.getHotelId())).toList(),
                true, !user.isMustChangePassword(), user.isTwoStepEnabled(),
                active.stream().map(RoleGrant::getHotelId).filter(h -> h != null).distinct().toList(),
                user.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listForBusiness() {
        String business = TenantContext.requireBusiness();
        return users.findByBusinessIdOrderByFullName(business).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(String userId) {
        return toResponse(find(userId));
    }

    @Transactional
    public UserCreatedResponse create(CreateUserRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        String businessId = caller.isPlatform() ? null : TenantContext.requireBusiness();
        if (request.role() == Role.OWNER && !caller.isPlatform()) {
            throw new ForbiddenException("Only H.M.S can create an owner; use Transfer ownership instead");
        }
        if (request.role().isPlatform() && !caller.isPlatform()) {
            throw new ForbiddenException("Only H.M.S can add admin accounts");
        }
        users.findByEmailIgnoreCase(request.email()).ifPresent(u -> {
            throw new ConflictException("Someone already signs in with " + request.email());
        });
        String temporary = Hashing.temporaryPassword();
        Instant expires = Instant.now().plus(temporaryPasswordHours, ChronoUnit.HOURS);
        UserAccount user = users.save(UserAccount.builder()
                .businessId(request.role().isPlatform() ? null : businessId)
                .fullName(request.fullName())
                .email(request.email().toLowerCase())
                .phone(request.phone())
                .passwordHash(passwordEncoder.encode(temporary))
                .mustChangePassword(true)
                .temporaryPasswordExpiresAt(expires)
                .language(request.language())
                .build());
        grant(user, request.role(), request.level() == null ? AccessLevel.FULL : request.level(), request.hotelId(), null, caller.getUserId());
        events.publish(KafkaTopics.USER_CREATED, UserCreatedEvent.builder()
                .businessId(user.getBusinessId()).userId(user.getId()).fullName(user.getFullName()).email(user.getEmail()).build());
        return new UserCreatedResponse(user.getId(), temporary, expires);
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
        UserAccount user = find(userId);
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
        String temporary = Hashing.temporaryPassword();
        Instant expires = Instant.now().plus(temporaryPasswordHours, ChronoUnit.HOURS);
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setMustChangePassword(true);
        user.setTemporaryPasswordExpiresAt(expires);
        users.save(user);
        return new UserCreatedResponse(user.getId(), temporary, expires);
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

    private void grant(UserAccount user, Role role, AccessLevel level, String hotelId, Instant expiresAt, String grantedBy) {
        String hotel = role == Role.OWNER || role.isPlatform() ? null : hotelId;
        if (hotel == null && !(role == Role.OWNER || role.isPlatform())) {
            hotel = TenantContext.current().map(t -> t.hotelId()).orElse(null);
        }
        if (hotel == null && !(role == Role.OWNER || role.isPlatform())) {
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

    private UserAccount find(String userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private UserResponse toResponse(UserAccount user) {
        List<UserResponse.Grant> gs = grants.findByUserId(user.getId()).stream()
                .map(g -> new UserResponse.Grant(g.getId(), g.getRole(), g.getLevel(), g.getHotelId(), g.isPaused(), g.getExpiresAt()))
                .toList();
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getBusinessId(),
                user.getStatus().name(), user.isMustChangePassword(), user.isTwoStepEnabled(), user.getPinHash() != null,
                user.getLanguage(), user.getLastSignInAt(), user.getCreatedAt(), gs);
    }
}
