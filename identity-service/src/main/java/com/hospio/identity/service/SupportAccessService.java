package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.GoneException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.SupportAccessGrantResponse;
import com.hospio.identity.dto.SupportAccessRequest;
import com.hospio.identity.dto.SupportAccessTokenResponse;
import com.hospio.identity.entity.SupportAccessGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.SupportAccessGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform admins may look into a business as its owner, with a logged reason, for a short while. The grant is
 * opened first (C7 · Why are you looking?), then turned into a read-only owner token: {@code sub} the admin,
 * {@code bid} the business, every hotel of the business, roles {@code OWNER:VIEW}, scope {@code business} and
 * {@code act} = the admin, expiring with the grant and never later than {@code support-token-max-minutes}.
 */
@Service
@RequiredArgsConstructor
public class SupportAccessService {

    private final SupportAccessGrantRepository repository;
    private final UserAccountRepository users;
    private final TenantClient tenantClient;
    private final JwtUtil jwtUtil;

    @Value("${hospio.auth.support-token-max-minutes:60}")
    private long maxTokenMinutes;

    @Transactional
    public SupportAccessGrant open(SupportAccessRequest request) {
        HospioUserDetails caller = requirePlatform();
        int minutes = request.minutes() == null ? 60 : Math.min(Math.max(request.minutes(), 1), 240);
        return repository.save(SupportAccessGrant.builder()
                .adminId(caller.getUserId())
                .businessId(request.businessId())
                .reason(request.reason())
                .expiresAt(Instant.now().plus(minutes, ChronoUnit.MINUTES))
                .build());
    }

    /** The read-only owner token for a grant the caller opened; 410 once the grant has run out. */
    @Transactional(readOnly = true)
    public SupportAccessTokenResponse token(String grantId) {
        HospioUserDetails caller = requirePlatform();
        SupportAccessGrant grant = repository.findById(grantId).orElseThrow(() -> new ResourceNotFoundException("Support access not found"));
        if (!grant.getAdminId().equals(caller.getUserId())) {
            throw new ForbiddenException("This support access was opened by someone else");
        }
        Instant now = Instant.now();
        if (!grant.getExpiresAt().isAfter(now)) {
            throw new GoneException("This support access has ended. Open a new one");
        }
        List<String> hotels = tenantClient.hotelIds(grant.getBusinessId()).orElse(List.of());
        Duration ttl = Duration.between(now, grant.getExpiresAt());
        Duration cap = Duration.ofMinutes(maxTokenMinutes);
        if (ttl.compareTo(cap) > 0) {
            ttl = cap;
        }
        String token = jwtUtil.generateToken(caller.getUserId(), caller.getEmail(), caller.getFullName(), grant.getBusinessId(), hotels,
                Map.of(Role.OWNER, AccessLevel.VIEW), JwtUtil.SCOPE_BUSINESS, caller.getUserId(), ttl.toMillis());
        return new SupportAccessTokenResponse(token, ttl.toSeconds(), hotels);
    }

    @Transactional(readOnly = true)
    public List<SupportAccessGrant> mine() {
        return repository.findByAdminIdOrderByCreatedAtDesc(requirePlatform().getUserId());
    }

    /** Every grant on a business, newest first, expired ones included, with the admin's name. */
    @Transactional(readOnly = true)
    public List<SupportAccessGrantResponse> forBusiness(String businessId) {
        requirePlatform();
        List<SupportAccessGrant> grants = repository.findByBusinessIdOrderByCreatedAtDesc(businessId);
        Map<String, String> names = users.findAllById(grants.stream().map(SupportAccessGrant::getAdminId).distinct().toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName, (a, b) -> a));
        Instant now = Instant.now();
        return grants.stream().map(g -> new SupportAccessGrantResponse(g.getId(), g.getAdminId(), names.get(g.getAdminId()), g.getBusinessId(),
                g.getReason(), g.getCreatedAt(), g.getExpiresAt(), !g.getExpiresAt().isAfter(now))).toList();
    }

    private static HospioUserDetails requirePlatform() {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform()) {
            throw new ForbiddenException("Only Hospio360 staff can use support access");
        }
        return caller;
    }
}
