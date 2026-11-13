package com.hospio.messaging.paging;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.billing.CommBilling;
import com.hospio.messaging.billing.CommInvoice;
import com.hospio.messaging.billing.CommInvoiceRepository;
import com.hospio.messaging.billing.CommPlan;
import com.hospio.messaging.billing.CommPlanRepository;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.repository.NotificationRepository;
import com.hospio.messaging.service.NotificationService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The scale fixes against real Postgres (needs Docker): the delivery queue's SKIP LOCKED claim, invoice numbering and late
 * messages, the guarded overdue move, notices inserted once per event.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "services.tenant.url=http://localhost:1/tenant",
        "services.identity.url=http://localhost:1/identity",
        "services.hotelconfig.url=http://localhost:1/hotel-config",
        "hospio.worker.enabled=false",
        "hospio.mail.poll-initial-delay-ms=3600000",
        "hospio.messaging.send-interval-ms=3600000",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class ConcurrencyDbTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("messaging-schema.sql");

    @Autowired
    DeliveryRecordRepository deliveries;
    @Autowired
    CommInvoiceRepository invoices;
    @Autowired
    CommPlanRepository plans;
    @Autowired
    CommBilling billing;
    @Autowired
    NotificationService notifications;
    @Autowired
    NotificationRepository notificationRows;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        TenantContext.set(Tenant.system("test"));
        jdbc.update("delete from messaging.deliveries");
        jdbc.update("delete from messaging.comm_invoices");
        jdbc.update("delete from messaging.comm_plans");
        jdbc.update("delete from messaging.notifications");
    }

    @AfterEach
    void out() {
        TenantContext.clear();
    }

    private DeliveryRecord queued(String to) {
        return deliveries.saveAndFlush(DeliveryRecord.builder().channel("EMAIL").toAddress(to).kind("RECEIPT").status(DeliveryRecord.Status.QUEUED)
                .bodyText("Thank you").nextAttemptAt(Instant.now().minusSeconds(5)).build());
    }

    @Test
    void twoWorkersNeverClaimTheSameDelivery() throws Exception {
        for (int i = 0; i < 5; i++) {
            queued("guest" + i + "@x.test");
        }
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        CompletableFuture<List<String>> a = CompletableFuture.supplyAsync(() -> TenantContext.runWith(Tenant.system("a"), () -> tx.execute(s -> {
            List<String> ids = deliveries.lockDue(Instant.now(), 3);
            deliveries.lease(ids, Instant.now().plusSeconds(300));
            aLocked.countDown();
            await(bDone);
            return ids;
        })));
        await(aLocked);
        List<String> b = TenantContext.runWith(Tenant.system("b"), () -> tx.execute(s -> {
            List<String> ids = deliveries.lockDue(Instant.now(), 10); // A's rows are locked: skipped, not waited for
            deliveries.lease(ids, Instant.now().plusSeconds(300));
            return ids;
        }));
        bDone.countDown();
        List<String> aIds = a.get(10, TimeUnit.SECONDS);
        assertThat(aIds).hasSize(3);
        assertThat(b).hasSize(2).doesNotContainAnyElementsOf(aIds);
        // Leased: nothing is due until the lease runs out, and each claim counted as an attempt.
        List<String> none = TenantContext.runWith(Tenant.system("c"), () -> tx.execute(s -> deliveries.lockDue(Instant.now(), 10)));
        assertThat(none).isEmpty();
        assertThat(deliveries.findAll()).allSatisfy(r -> assertThat(r.getAttempts()).isEqualTo(1));
    }

    private static void await(CountDownLatch l) {
        try {
            assertThat(l.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private DeliveryRecord billable(String hotel, String status, LocalDateTime createdLagos) {
        DeliveryRecord r = deliveries.saveAndFlush(DeliveryRecord.builder().businessId("b1").hotelId(hotel).channel("EMAIL").toAddress("g@x.test")
                .kind("RECEIPT").status(DeliveryRecord.Status.valueOf(status)).billable(true).sentVia("EMAIL").unitPrice(new BigDecimal("5.00")).build());
        jdbc.update("update messaging.deliveries set created_at = ? where id = ?", Timestamp.from(createdLagos.atZone(CommBilling.LAGOS).toInstant()), r.getId());
        return r;
    }

    @Test
    void numbersFollowEachOtherAndALateMessageRollsIntoTheNextInvoice() {
        billable("h1", "SENT", LocalDateTime.of(2026, 10, 15, 12, 0));
        DeliveryRecord late = billable("h1", "QUEUED", LocalDateTime.of(2026, 10, 31, 23, 50)); // goes out after October is invoiced
        billable("h2", "DELIVERED", LocalDateTime.of(2026, 10, 2, 9, 0));
        billable("h1", "SENT", LocalDateTime.of(2026, 11, 3, 9, 0));

        CommInvoice oct1 = billing.issue("h1", "b1", YearMonth.of(2026, 10)).orElseThrow();
        CommInvoice oct2 = billing.issue("h2", "b1", YearMonth.of(2026, 10)).orElseThrow();
        assertThat(oct1.getNumber()).isEqualTo("MSG-202610-0001");
        assertThat(oct2.getNumber()).isEqualTo("MSG-202610-0002");
        assertThat(oct1.getEmailCount()).isEqualTo(1);
        assertThat(oct1.getTotalAmount()).isEqualByComparingTo("5");

        jdbc.update("update messaging.deliveries set status = 'SENT' where id = ?", late.getId());
        assertThat(billing.issue("h1", "b1", YearMonth.of(2026, 10))).isEmpty(); // October is done; the late one waits

        CommInvoice nov = billing.issue("h1", "b1", YearMonth.of(2026, 11)).orElseThrow();
        assertThat(nov.getNumber()).isEqualTo("MSG-202611-0001");
        assertThat(nov.getEmailCount()).isEqualTo(2); // November's own + October's late one
        assertThat(nov.getTotalAmount()).isEqualByComparingTo("10");
        CommBilling.InvoiceView view = billing.invoice(nov.getId(), null);
        assertThat(view.lines().stream().mapToLong(CommBilling.Line::count).sum()).isEqualTo(2);
        assertThat(deliveries.findById(late.getId()).orElseThrow().getInvoiceId()).isEqualTo(nov.getId());
        // the issue notice reached the owners and managers once, however often it is sent
        assertThat(notificationRows.findAll()).filteredOn(n -> "COMM_INVOICE".equals(n.getKind()) && "h1".equals(n.getHotelId())).hasSize(4);
    }

    @Test
    void anInvoicePaidBeforeTheOverdueCheckStaysPaid() {
        CommInvoice paid = invoices.saveAndFlush(invoice("h1", "MSG-202609-0001"));
        CommInvoice late = invoices.saveAndFlush(invoice("h2", "MSG-202609-0002"));
        plans.saveAndFlush(CommPlan.builder().businessId("b1").hotelId("h1").enabled(true).build());
        // paid by the webhook after the overdue check read it
        jdbc.update("update messaging.comm_invoices set status = 'PAID', paid_via = 'PAYSTACK' where id = ?", paid.getId());
        Integer moved = tx.execute(s -> invoices.moveStatus(paid.getId(), CommInvoice.Status.DUE, CommInvoice.Status.OVERDUE, Instant.now()));
        assertThat(moved).isZero();

        assertThat(billing.suspendOverdue()).isEqualTo(1);
        assertThat(invoices.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(CommInvoice.Status.PAID);
        assertThat(invoices.findById(paid.getId()).orElseThrow().getPaidVia()).isEqualTo("PAYSTACK");
        assertThat(invoices.findById(late.getId()).orElseThrow().getStatus()).isEqualTo(CommInvoice.Status.OVERDUE);
        assertThat(plans.findByHotelId("h1").orElseThrow().getSuspendedAt()).isNull();
        assertThat(plans.findByHotelId("h2").orElseThrow().getSuspendedAt()).isNotNull();
        assertThat(billing.suspendOverdue()).isZero(); // a second run (another instance) does nothing
    }

    private static CommInvoice invoice(String hotel, String number) {
        return CommInvoice.builder().businessId("b1").hotelId(hotel).number(number).month("2026-09").emailAmount(BigDecimal.ONE).smsAmount(BigDecimal.ZERO)
                .whatsappAmount(BigDecimal.ZERO).totalAmount(BigDecimal.ONE).status(CommInvoice.Status.DUE).issuedAt(Instant.now())
                .dueOn(LocalDate.now(CommBilling.LAGOS).minusDays(2)).build();
    }

    @Test
    void aNoticeIsInsertedOncePerEventEvenFromTwoThreads() throws Exception {
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(CompletableFuture.runAsync(() -> TenantContext.runWith(Tenant.systemFor("b1", "h1", null, "test"),
                    () -> notifications.notifyRoles("11111111-1111-1111-1111-111111111111", "BOOKING", "New booking", "x", "/b", "FRONT_OFFICE", "MANAGER"))));
        }
        for (CompletableFuture<Void> r : runs) {
            r.get(10, TimeUnit.SECONDS);
        }
        Set<String> roles = new HashSet<>();
        notificationRows.findAll().forEach(n -> roles.add(n.getRole()));
        assertThat(notificationRows.count()).isEqualTo(2);
        assertThat(roles).containsExactlyInAnyOrder("FRONT_OFFICE", "MANAGER");
    }
}
