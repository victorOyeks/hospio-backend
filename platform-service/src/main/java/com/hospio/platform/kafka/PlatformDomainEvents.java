package com.hospio.platform.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.BusinessCreatedEvent;
import com.hospio.common.kafka.events.BusinessStageChangedEvent;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.PaymentDeclinedEvent;
import com.hospio.common.kafka.events.UserSignedInEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.service.AlertService;
import com.hospio.platform.service.MarketerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What the admin portal does with events beyond logging them: marketer referrals and agreements follow a
 * business's life, a marketer's first sign-in activates their profile, and declined payments raise (or
 * clear) the PAYMENTS_FAILING alert. Called from {@link PlatformEventListener} after the activity log write,
 * so a burst count already includes the event in hand. Runs as the platform: these tables span businesses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformDomainEvents {

    private final MarketerService marketers;
    private final AlertService alerts;

    public void on(String topic, HospioEvent event) {
        TenantContext.runWith(Tenant.system("platform-events"), () -> {
            switch (topic) {
                case KafkaTopics.BUSINESS_CREATED -> {
                    if (event instanceof BusinessCreatedEvent e) {
                        marketers.onBusinessCreated(e.getBusinessId(), e.getName(), e.getMarketerId(), e.getReferralId());
                    }
                }
                case KafkaTopics.BUSINESS_STAGE_CHANGED -> {
                    if (event instanceof BusinessStageChangedEvent e && "LIVE".equals(e.getToStage()) && e.getBusinessId() != null) {
                        marketers.onBusinessLive(e.getBusinessId());
                    }
                }
                case KafkaTopics.USER_SIGNED_IN -> {
                    if (event instanceof UserSignedInEvent e && e.getBusinessId() == null) {
                        marketers.onUserSignedIn(e.getUserId() != null ? e.getUserId() : e.getActorId());
                    }
                }
                case KafkaTopics.PAYMENT_DECLINED -> {
                    if (event instanceof PaymentDeclinedEvent e) {
                        alerts.onPaymentDeclined(e.getBusinessId(), e.getHotelId());
                    }
                }
                case KafkaTopics.PAYMENT_APPROVED -> {
                    if (event instanceof PaymentApprovedEvent e) {
                        alerts.onPaymentApproved(e.getHotelId());
                    }
                }
                default -> { }
            }
        });
    }
}
