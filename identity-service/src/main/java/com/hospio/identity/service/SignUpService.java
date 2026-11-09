package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
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
import com.hospio.identity.dto.PhoneCodeResponse;
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
import org.springframework.util.StringUtils;

/**
 * Self-serve sign-up: the business is created in tenant-service with an id chosen here, then the owner
 * account (their own password, phone not yet verified) and the business-wide OWNER grant, a phone code is
 * issued so the verify screen already has one, and the owner is signed in. Runs as the system: there is no
 * tenant until the business exists.
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
    private final PhoneVerificationService phoneVerification;

    @Transactional
    public AuthResponse signUp(SignUpRequest request) {
        return TenantContext.runWith(Tenant.system("sign-up"), () -> {
            String email = request.email().trim().toLowerCase();
            if (!Phones.meaningfulName(request.fullName())) {
                throw new BadRequestException("Full name must be a real name, with letters");
            }
            if (!Phones.meaningfulName(request.businessName())) {
                throw new BadRequestException("Business name must contain letters, not only numbers or symbols");
            }
            if (!StringUtils.hasText(request.city()) || !Phones.meaningfulName(request.city())) {
                throw new BadRequestException("Enter the city");
            }
            String phone = Phones.clean(request.phone(), "Phone");
            if (phone == null) {
                throw new BadRequestException("Enter your phone number");
            }
            users.findByEmailIgnoreCase(email).ifPresent(u -> {
                throw new ConflictException("Someone already signs in with " + email + ". Sign in instead");
            });
            if (users.countByPhoneKey(Phones.key(phone)) > 0) {
                throw new ConflictException("This phone number is already in use on another account. Use a different number or sign in");
            }
            String businessId = UUID.randomUUID().toString();
            UserAccount owner = UserAccount.builder()
                    .businessId(businessId)
                    .fullName(request.fullName().trim())
                    .email(email)
                    .phone(phone)
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .mustChangePassword(false)
                    .phoneVerified(false)
                    .status(UserAccount.Status.ACTIVE)
                    .build();
            owner.setId(UUID.randomUUID().toString());
            owner = users.save(owner);
            grants.save(RoleGrant.builder().userId(owner.getId()).businessId(businessId).hotelId(null)
                    .role(Role.OWNER).level(AccessLevel.FULL).grantedBy(owner.getId()).build());

            tenantClient.createBusiness(new TenantClient.NewBusiness(businessId, request.businessName().trim(), request.city().trim(),
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
            PhoneCodeResponse code = phoneVerification.issue(owner);
            AuthResponse response = tokenIssuer.issue(owner);
            events.publish(KafkaTopics.USER_SIGNED_IN, UserSignedInEvent.builder()
                    .businessId(businessId).actorId(userId).actorName(name).userId(userId).method("sign-up").build());
            return code == null ? response : response.withDeliveries(code.deliveries());
        });
    }
}
