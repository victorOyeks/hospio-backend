package com.hospio.payment.race;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.client.PlatformClient;
import com.hospio.payment.client.TenantClient;
import com.hospio.payment.dto.AdminPaymentDtos;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.ReceiptRepository;
import com.hospio.payment.repository.SettlementRepository;
import com.hospio.payment.service.AdminPaymentQueryService;
import com.hospio.payment.service.PaymentService;
import com.hospio.payment.service.SettlementService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The races of the 2026-11-13 audit against a real Postgres: the same webhook delivered twice at once approves once (one receipt, one
 * event); settlements are summed in SQL and a second run skips. Needs Docker (skipped without it).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "hospio.worker.enabled=false",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class PaymentRaceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("payment-schema.sql");

    @MockBean
    EventPublisher events;
    @MockBean
    TenantClient tenant;
    @MockBean
    PlatformClient platform;

    @Autowired
    PaymentRequestRepository payments;
    @Autowired
    ReceiptRepository receipts;
    @Autowired
    SettlementRepository settlements;
    @Autowired
    PaymentService paymentService;
    @Autowired
    SettlementService settlementService;
    @Autowired
    AdminPaymentQueryService admin;

    @BeforeEach
    void asPlatform() {
        TenantContext.set(Tenant.system("test"));
        when(platform.feeSettings()).thenReturn(PlatformClient.FeeSettings.defaults());
        when(tenant.businessName(anyString())).thenReturn(Optional.of("Biz"));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private PaymentRequest pay(String business, String hotel, PaymentMethod method, String provider, String amount, PaymentStatus status, Instant resolvedAt) {
        return payments.saveAndFlush(PaymentRequest.builder().businessId(business).hotelId(hotel).method(method).provider(provider)
                .amount(new BigDecimal(amount)).status(status).requestedBy("u1").role(Role.FRONT_OFFICE).resolvedAt(resolvedAt)
                .providerRef("REF-" + System.nanoTime()).build());
    }

    @Test
    void theSameWebhookTwiceAtOnceApprovesOnce() throws Exception {
        PaymentRequest p = pay("b1", "h1", PaymentMethod.CARD, "logging", "5000", PaymentStatus.PROCESSING, null);
        int n = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            done.add(pool.submit((Callable<Void>) () -> {
                go.await();
                paymentService.onWebhook("logging", Map.of("status", "APPROVED", "paymentRequestId", p.getId(), "reference", p.getProviderRef()), Map.of());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();

        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(receipts.findByPaymentIdIn(List.of(p.getId()))).hasSize(1);
        verify(events, times(1)).publish(eq(KafkaTopics.PAYMENT_APPROVED), any());
    }

    @Test
    void settlementsAreSummedInTheDatabaseAndASecondRunSkips() {
        LocalDate day = LocalDate.of(2026, 11, 7);
        Instant noon = day.atTime(12, 0).atZone(SettlementService.LAGOS).toInstant();
        pay("s1", "h1", PaymentMethod.CARD, "Logging", "100000", PaymentStatus.APPROVED, noon);
        pay("s1", "h2", PaymentMethod.TRANSFER, "logging", "50000", PaymentStatus.APPROVED, noon);
        pay("s1", "h1", PaymentMethod.CASH, null, "999", PaymentStatus.APPROVED, noon);
        pay("s1", "h1", PaymentMethod.TRANSFER, null, "777", PaymentStatus.APPROVED, noon);
        pay("s2", "h3", PaymentMethod.CARD, "moniepoint", "20000", PaymentStatus.APPROVED, noon);
        pay("s2", "h3", PaymentMethod.CARD, "moniepoint", "1", PaymentStatus.DECLINED, noon);

        AdminPaymentDtos.RunResult first = settlementService.run(day);
        AdminPaymentDtos.RunResult again = settlementService.run(day);

        assertThat(first.created()).isEqualTo(2);
        assertThat(again.created()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
        Settlement s1 = settlements.findByBusinessIdOrderByPeriodDateDesc("s1").get(0);
        assertThat(s1.getGross()).isEqualByComparingTo("150000");
        assertThat(s1.getPaymentCount()).isEqualTo(2);
        assertThat(s1.getHotelId()).isNull();
        assertThat(s1.getBusinessName()).isEqualTo("Biz");

        AdminPaymentDtos.Summary sum = admin.summary("s1", day, day);
        assertThat(sum.gross()).isEqualByComparingTo("151776");
        assertThat(sum.count()).isEqualTo(4);
        assertThat(sum.byProvider()).extracting(AdminPaymentDtos.ProviderTotal::provider).containsExactlyInAnyOrder("logging", "cash");
    }

    @Test
    void aHotelsFiguresForTodayAreCountedInTheDatabase() {
        pay("o1", "ho1", PaymentMethod.CASH, null, "1200", PaymentStatus.APPROVED, Instant.now());
        pay("o1", "ho1", PaymentMethod.CARD, "logging", "800", PaymentStatus.APPROVED, Instant.now());
        pay("o1", "ho1", PaymentMethod.CARD, "logging", "300", PaymentStatus.PROCESSING, null);

        AdminPaymentDtos.HotelOverview o = admin.hotelOverview("ho1");

        assertThat(o.paymentsToday().count()).isEqualTo(2);
        assertThat(o.paymentsToday().gross()).isEqualByComparingTo("2000");
        assertThat(o.pendingPayments()).isEqualTo(1);
    }
}
