package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.RoleGrantedEvent;
import com.hospio.common.kafka.events.UserCreatedEvent;
import com.hospio.common.kafka.events.UserSignedInEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.SignUpRequest;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-serve sign-up: the business is created in tenant-service with an id chosen here, then the owner
 * account (their own password, phone not yet verified) and the business-wide OWNER grant, and the
 * owner is signed in. Runs as the system: there is no tenant until the business exists.
 */
@Service
@RequiredArgsConstructor
public class SignUpService {

    public static final String SOURCE_SELF_SERVE = "SELF_SERVE";

    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final TenantClient tenantClient;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final EventPublisher events;

    @Transactional
    public AuthResponse signUp(SignUpRequest request) {
        return TenantContext.runWith(Tenant.system("sign-up"), () -> {
            String email = request.email().toLowerCase();
            users.findByEmailIgnoreCase(email).ifPresent(u -> {
                throw new ConflictException("Someone already signs in with " + email + ". Sign in instead");
            });
            String businessId = UUID.randomUUID().toString();
            UserAccount owner = UserAccount.builder()
                    .businessId(businessId)
                    .fullName(request.fullName().trim())
                    .email(email)
                    .phone(request.phone())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .mustChangePassword(false)
                    .phoneVerified(false)
                    .status(UserAccount.Status.ACTIVE)
                    .build();
            owner.setId(UUID.randomUUID().toString());
            owner = users.save(owner);
            grants.save(RoleGrant.builder().userId(owner.getId()).businessId(businessId).hotelId(null)
                    .role(Role.OWNER).level(AccessLevel.FULL).grantedBy(owner.getId()).build());

            tenantClient.createBusiness(new TenantClient.NewBusiness(businessId, request.businessName().trim(), request.city(),
                    owner.getId(), owner.getFullName(), owner.getEmail(), owner.getPhone(), SOURCE_SELF_SERVE));

            owner.setLastSignInAt(Instant.now());
            users.save(owner);
            String userId = owner.getId();
            String name = owner.getFullName();
            events.publish(KafkaTopics.USER_CREATED, UserCreatedEvent.builder()
                    .businessId(businessId).actorId(userId).actorName(name).userId(userId).fullName(name).email(owner.getEmail()).build());
            events.publish(KafkaTopics.ROLE_GRANTED, RoleGrantedEvent.builder()
                    .businessId(businessId).actorId(userId).actorName(name).userId(userId).userName(name)
                    .role(Role.OWNER.name()).level(AccessLevel.FULL.name()).build());
            AuthResponse response = tokenIssuer.issue(owner);
            events.publish(KafkaTopics.USER_SIGNED_IN, UserSignedInEvent.builder()
                    .businessId(businessId).actorId(userId).actorName(name).userId(userId).method("sign-up").build());
            return response;
        });
    }
}
