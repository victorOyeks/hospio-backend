package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.HandoverAcceptedEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Handover;
import com.hospio.tenant.repository.HandoverRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import com.hospio.tenant.repository.SetupTemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** K3 (18 Nov 2026 audit): accepting the handover tells the platform (event) and welcomes the owner by email, once. */
class HandoverAcceptTest {

    private final HandoverRepository handovers = mock(HandoverRepository.class);
    private final BusinessService businesses = mock(BusinessService.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final OnboardingService service = new OnboardingService(mock(OnboardingDraftRepository.class), handovers,
            mock(SetupTemplateRepository.class), businesses, new DraftData(new com.fasterxml.jackson.databind.ObjectMapper()),
            mock(IdentityClient.class), events, new HandoverPack("https://web-staging.hospio360.com"), deliveries);
    private final Handover handover = new Handover();

    @BeforeEach
    void owner() {
        HospioUserDetails owner = new HospioUserDetails("o1", "qa.owner@hospio-qa.test", "Tunde Bakare", "biz-1", List.of("h1"),
                Map.of(Role.OWNER, AccessLevel.FULL), JwtUtil.SCOPE_BUSINESS);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        when(handovers.findByBusinessId("biz-1")).thenReturn(Optional.of(handover));
        Business b = Business.builder().name("QA Hotels").ownerName("Tunde Bakare").ownerEmail("qa.owner@hospio-qa.test").accountManagerId("am-1").build();
        b.setId("biz-1");
        when(businesses.find("biz-1")).thenReturn(b);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theFirstAcceptanceTellsThePlatformAndEmailsTheOwnerFromHospio360() {
        AtomicReference<String> businessInContext = new AtomicReference<>("unset");
        when(deliveries.send(any(Delivery.class))).thenAnswer(i -> {
            businessInContext.set(TenantContext.current().map(t -> t.businessId()).orElse(null));
            return List.of();
        });

        service.accept();

        ArgumentCaptor<HandoverAcceptedEvent> event = ArgumentCaptor.forClass(HandoverAcceptedEvent.class);
        verify(events).publish(eq(KafkaTopics.HANDOVER_ACCEPTED), event.capture());
        assertThat(event.getValue().getAccountManagerId()).isEqualTo("am-1");
        assertThat(event.getValue().getOwnerName()).isEqualTo("Tunde Bakare");
        ArgumentCaptor<Delivery> mail = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries).send(mail.capture());
        assertThat(mail.getValue().channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(mail.getValue().to()).isEqualTo("qa.owner@hospio-qa.test");
        assertThat(mail.getValue().kind()).isEqualTo(Delivery.Kind.WELCOME);
        assertThat(mail.getValue().subject()).isEqualTo("Welcome to Hospio360 · QA Hotels is live");
        assertThat(mail.getValue().text()).startsWith("Hello Tunde,").contains("https://web-staging.hospio360.com");
        // Sent with no business in the context: the From name is Hospio360's.
        assertThat(businessInContext.get()).isNull();
    }

    @Test
    void acceptingAgainSendsNothingMore() {
        handover.setAcceptedAt(java.time.Instant.now());
        service.accept();
        verify(events, never()).publish(any(), any());
        verify(deliveries, never()).send(any(Delivery.class));
        verify(businesses, times(1)).moveStage(any(), eq(Business.Stage.LIVE), any());
    }
}
