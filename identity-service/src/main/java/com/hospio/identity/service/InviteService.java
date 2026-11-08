package com.hospio.identity.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.GoneException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.UserSignedInEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AcceptInviteRequest;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.InviteResponse;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.entity.Invite;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.InviteRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invite links. Every created login gets one (7 days): /accept-invite?token= on the right app lets the
 * person choose a password and signs them in. Only the SHA-256 of the token is kept. The public
 * endpoints run as the system because nobody is signed in yet.
 */
@Service
@RequiredArgsConstructor
public class InviteService {

    private final InviteRepository invites;
    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final TenantClient tenantClient;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final EventPublisher events;

    @Value("${hospio.auth.invite-days:7}")
    private long inviteDays;

    public record Issued(String token, Instant expiresAt) {
    }

    /** A fresh invite for a user; the raw token is returned once and never stored. */
    @Transactional
    public Issued issue(UserAccount user, String createdBy) {
        String raw = Hashing.randomToken();
        Instant expires = Instant.now().plus(inviteDays, ChronoUnit.DAYS);
        invites.save(Invite.builder()
                .userId(user.getId())
                .businessId(user.getBusinessId())
                .tokenHash(Hashing.sha256(raw))
                .expiresAt(expires)
                .createdBy(createdBy)
                .build());
        return new Issued(raw, expires);
    }

    @Transactional(readOnly = true)
    public InviteResponse get(String token) {
        return TenantContext.runWith(Tenant.system("invite-lookup"), () -> {
            Invite invite = usable(token);
            UserAccount user = users.findById(invite.getUserId()).orElseThrow(() -> new ResourceNotFoundException("Invite not found"));
            RoleGrant grant = grants.findByUserId(user.getId()).stream()
                    .sorted(Comparator.comparing(RoleGrant::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .findFirst().orElse(null);
            String businessName = user.getBusinessId() == null ? null : tenantClient.businessName(user.getBusinessId()).orElse(null);
            String hotelName = grant == null || grant.getHotelId() == null ? null
                    : tenantClient.hotel(grant.getHotelId()).map(TenantClient.HotelInfo::name).orElse(null);
            return new InviteResponse(user.getFullName(), user.getEmail(), grant == null ? null : grant.getRole().name(),
                    businessName, hotelName, invite.getExpiresAt(), invite.isAccepted());
        });
    }

    /** Sets the password, clears the temporary one, marks the invite used and signs the person in. */
    @Transactional
    public AuthResponse accept(AcceptInviteRequest request) {
        return TenantContext.runWith(Tenant.system("accept-invite"), () -> {
            Invite invite = usable(request.token());
            UserAccount user = users.findById(invite.getUserId()).orElseThrow(() -> new ResourceNotFoundException("Invite not found"));
            if (user.getStatus() != UserAccount.Status.ACTIVE) {
                throw new ForbiddenException("This account is " + user.getStatus().name().toLowerCase() + ". Talk to your manager");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setMustChangePassword(false);
            user.setTemporaryPasswordExpiresAt(null);
            user.setLastSignInAt(Instant.now());
            users.save(user);
            invite.setAcceptedAt(Instant.now());
            invites.save(invite);
            AuthResponse response = tokenIssuer.issue(user);
            events.publish(KafkaTopics.USER_SIGNED_IN, UserSignedInEvent.builder()
                    .businessId(user.getBusinessId()).actorId(user.getId()).actorName(user.getFullName())
                    .userId(user.getId()).method("invite").build());
            return response;
        });
    }

    /** Latest invite per user: PENDING, ACCEPTED, EXPIRED, or NONE when never issued. */
    @Transactional(readOnly = true)
    public Map<String, UserResponse.InviteStatus> statusFor(Collection<String> userIds) {
        Map<String, UserResponse.InviteStatus> out = new HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        List<Invite> all = invites.findByUserIdInOrderByCreatedAtDesc(userIds);
        for (Invite i : all) {
            out.putIfAbsent(i.getUserId(), statusOf(i));
        }
        for (String id : userIds) {
            out.putIfAbsent(id, UserResponse.InviteStatus.NONE);
        }
        return out;
    }

    static UserResponse.InviteStatus statusOf(Invite i) {
        if (i.isAccepted()) {
            return UserResponse.InviteStatus.ACCEPTED;
        }
        return i.isExpired() ? UserResponse.InviteStatus.EXPIRED : UserResponse.InviteStatus.PENDING;
    }

    private Invite usable(String token) {
        Invite invite = invites.findByTokenHash(Hashing.sha256(token))
                .orElseThrow(() -> new ResourceNotFoundException("We don't know that invite link"));
        if (invite.isAccepted()) {
            throw new GoneException("This invite was already used. Sign in instead");
        }
        if (invite.isExpired()) {
            throw new GoneException("This invite has expired. Ask for a new one");
        }
        return invite;
    }
}
