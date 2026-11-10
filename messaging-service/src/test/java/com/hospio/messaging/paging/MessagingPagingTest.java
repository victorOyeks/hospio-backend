package com.hospio.messaging.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.dto.ThreadDtos;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.Notification;
import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.NotificationRepository;
import com.hospio.messaging.service.NotificationService;
import com.hospio.messaging.service.StaffThreadService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Notifications and staff threads paged in Postgres: the window, the filters, the counts, no gaps between pages. Needs Docker. */
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
class MessagingPagingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("messaging-schema.sql");

    @Autowired
    NotificationRepository repo;
    @Autowired
    ConversationRepository conversations;
    @Autowired
    NotificationService service;
    @Autowired
    StaffThreadService threads;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    com.hospio.messaging.repository.DeliveryRecordRepository deliveries;
    @Autowired
    com.hospio.messaging.repository.MessageRepository messages;
    @Autowired
    com.hospio.messaging.service.GuestInboxService inbox;

    @BeforeEach
    void signIn() {
        jdbc.update("delete from messaging.notifications");
        jdbc.update("delete from messaging.messages");
        jdbc.update("delete from messaging.conversations");
        jdbc.update("delete from messaging.deliveries");
        HospioUserDetails u = new HospioUserDetails("u1", "u1@x.ng", "Femi", "b1", List.of("h1"), Map.of(Role.FINANCE, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private Notification add(String userId, String role, String hotel, String title, int daysAgo, boolean read) {
        Notification n = repo.saveAndFlush(Notification.builder().businessId("b1").hotelId(hotel).userId(userId).role(role).kind("K")
                .title(title).body("body " + title).readAt(read ? Instant.now() : null).build());
        jdbc.update("update messaging.notifications set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS).minusSeconds(Long.parseLong(title.replaceAll("\\D", "0")))), n.getId());
        return n;
    }

    @Test
    void notificationsArePagedWithTheWindowFiltersAndCounts() {
        for (int i = 1; i <= 30; i++) {
            add(i % 2 == 0 ? "u1" : null, i % 2 == 0 ? null : "FINANCE", "h1", "QA " + i, 1, i % 3 == 0);
        }
        add(null, "BAR", "h1", "Not mine 1", 1, false);        // a role I don't hold
        add(null, "FINANCE", "h2", "Other hotel 1", 1, false); // another hotel
        add("u1", null, "h1", "Old 1", 120, false);              // outside the 90 days

        PageWithCounts<Notification> p0 = service.page(new NotificationService.Filter(null, null, null, null, null), 0, 25);
        PageWithCounts<Notification> p1 = service.page(new NotificationService.Filter(null, null, null, null, null), 1, 25);
        assertThat(p0.totalElements()).isEqualTo(30);
        assertThat(p0.content()).hasSize(25);
        assertThat(p1.content()).hasSize(5);
        Set<String> ids = new HashSet<>();
        p0.content().forEach(n -> ids.add(n.getId()));
        p1.content().forEach(n -> ids.add(n.getId()));
        assertThat(ids).hasSize(30);
        assertThat(p0.counts()).isEqualTo(p1.counts());
        assertThat(p0.counts().get("all")).isEqualTo(30);
        assertThat(p0.counts().get("unread")).isEqualTo(20);
        assertThat(p0.content().get(0).getCreatedAt()).isAfterOrEqualTo(p0.content().get(1).getCreatedAt());

        assertThat(service.page(new NotificationService.Filter(true, null, null, null, null), 0, 25).totalElements()).isEqualTo(20);
        assertThat(service.page(new NotificationService.Filter(null, null, "qa 1", null, null), 0, 25).totalElements()).isEqualTo(11); // 1, 10-19
        assertThat(service.page(new NotificationService.Filter(null, null, null, java.time.LocalDate.now().minusDays(200), null), 0, 25)
                .totalElements()).isEqualTo(31);
        assertThat(service.unreadCount()).isEqualTo(21); // the badge counts every unread one, any age
        assertThatThrownBy(() -> service.page(new NotificationService.Filter(null, null, null, null, null), 0, 101))
                .isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
    }

    @Test
    void threadsArePagedAndSearched() {
        HospioUserDetails owner = new HospioUserDetails("own", "o@x.ng", "Godwin", "b1", List.of("h1"), Map.of(Role.OWNER, AccessLevel.FULL),
                JwtUtil.SCOPE_BUSINESS);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        for (int i = 1; i <= 27; i++) {
            conversations.saveAndFlush(Conversation.builder().businessId("b1").hotelId("h1").kind(Conversation.Kind.STAFF)
                    .channel(com.hospio.common.enums.NotificationChannel.IN_APP).subject("Diesel " + i).createdBy("own").createdByName("Godwin")
                    .audience("PERSON").assignedTo("t" + i).recipientName(i == 7 ? "Tunde Bakare" : "Person " + i)
                    .lastMessageAt(Instant.now().minusSeconds(i * 60L)).build());
        }
        PagedResponse<ThreadDtos.ThreadRow> p0 = threads.page(null, 0, 25);
        PagedResponse<ThreadDtos.ThreadRow> p1 = threads.page(null, 1, 25);
        assertThat(p0.totalElements()).isEqualTo(27);
        assertThat(p0.content().get(0).subject()).isEqualTo("Diesel 1");
        assertThat(p1.content()).extracting(ThreadDtos.ThreadRow::subject).containsExactly("Diesel 26", "Diesel 27");
        assertThat(threads.page("tunde", 0, 25).content()).extracting(ThreadDtos.ThreadRow::subject).containsExactly("Diesel 7");
        assertThat(threads.page("100%", 0, 25).totalElements()).isZero();
    }

    @Test
    void theDeliveryLogIsSearchedByAddressAndSubjectWithWildcardsTakenLiterally() {
        HospioUserDetails mgr = new HospioUserDetails("m1", "m@x.ng", "Tunde", "b1", List.of("h1"), Map.of(Role.MANAGER, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(mgr, null, mgr.getAuthorities()));
        deliveries.saveAndFlush(com.hospio.messaging.entity.DeliveryRecord.builder().businessId("b1").hotelId("h1").channel("EMAIL")
                .toAddress("qa.em.z2k0g@hospio-qa.test").subject("Your booking").kind("GUEST").status(com.hospio.messaging.entity.DeliveryRecord.Status.SENT).build());
        deliveries.saveAndFlush(com.hospio.messaging.entity.DeliveryRecord.builder().businessId("b1").hotelId("h1").channel("EMAIL")
                .toAddress("ade@x.ng").subject("100% off_today").kind("GUEST").status(com.hospio.messaging.entity.DeliveryRecord.Status.FAILED).build());
        deliveries.saveAndFlush(com.hospio.messaging.entity.DeliveryRecord.builder().businessId("b1").hotelId("h1").channel("EMAIL")
                .toAddress("bo@x.ng").subject(null).kind("GUEST").status(com.hospio.messaging.entity.DeliveryRecord.Status.SENT).build());
        deliveries.saveAndFlush(com.hospio.messaging.entity.DeliveryRecord.builder().businessId("b1").hotelId("h2").channel("EMAIL")
                .toAddress("qa.em.other@hospio-qa.test").subject("Other hotel").kind("GUEST").status(com.hospio.messaging.entity.DeliveryRecord.Status.SENT).build());

        assertThat(inbox.deliveryLog(null, null, "QA.EM.Z2K0G@hospio-qa.test", 0, 20).content()).extracting(com.hospio.messaging.entity.DeliveryRecord::getToAddress)
                .containsExactly("qa.em.z2k0g@hospio-qa.test");
        assertThat(inbox.deliveryLog(null, null, "100%", 0, 20).content()).hasSize(1);
        assertThat(inbox.deliveryLog(null, null, "off_t", 0, 20).content()).hasSize(1);
        assertThat(inbox.deliveryLog(null, null, "o%f", 0, 20).content()).isEmpty();
        assertThat(inbox.deliveryLog(null, null, "a\\b'", 0, 20).content()).isEmpty();
        assertThat(inbox.deliveryLog("failed", null, "x.ng", 0, 20).content()).extracting(com.hospio.messaging.entity.DeliveryRecord::getToAddress)
                .containsExactly("ade@x.ng");
        PageWithCounts<com.hospio.messaging.entity.DeliveryRecord> all = inbox.deliveryLog(null, null, "x.ng", 0, 20);
        assertThat(all.counts()).containsEntry("all", 2L).containsEntry("sent", 1L).containsEntry("failed", 1L);
    }

    @Test
    void theInboxBadgeCountsUnreadMessagesFromTheOtherSideOverTheCallersThreads() {
        // Femi (u1, signed in) has two threads from the owner and one from someone else to another person.
        Conversation a = conversations.saveAndFlush(Conversation.builder().businessId("b1").hotelId("h1").kind(Conversation.Kind.STAFF)
                .channel(com.hospio.common.enums.NotificationChannel.IN_APP).subject("Diesel").createdBy("own").createdByName("Godwin")
                .audience("PERSON").assignedTo("u1").recipientName("Femi").lastMessageAt(Instant.now()).build());
        Conversation b = conversations.saveAndFlush(Conversation.builder().businessId("b1").hotelId("h1").kind(Conversation.Kind.STAFF)
                .channel(com.hospio.common.enums.NotificationChannel.IN_APP).subject("Float").createdBy("own").createdByName("Godwin")
                .audience("PERSON").assignedTo("u1").recipientName("Femi").recipientReadAt(Instant.now().plusSeconds(60))
                .lastMessageAt(Instant.now()).build());
        Conversation other = conversations.saveAndFlush(Conversation.builder().businessId("b1").hotelId("h1").kind(Conversation.Kind.STAFF)
                .channel(com.hospio.common.enums.NotificationChannel.IN_APP).subject("Not mine").createdBy("own").createdByName("Godwin")
                .audience("PERSON").assignedTo("u9").recipientName("Grace").lastMessageAt(Instant.now()).build());
        for (Conversation c : List.of(a, a, b, other)) {
            messages.saveAndFlush(com.hospio.messaging.entity.Message.builder().businessId("b1").hotelId("h1").conversationId(c.getId())
                    .direction(com.hospio.messaging.entity.Message.Direction.OUT).senderId("own").senderName("Godwin").body("Hi").build());
        }
        messages.saveAndFlush(com.hospio.messaging.entity.Message.builder().businessId("b1").hotelId("h1").conversationId(a.getId())
                .direction(com.hospio.messaging.entity.Message.Direction.OUT).senderId("u1").senderName("Femi").body("My own reply").build());
        // a: two from the owner, unread; b: read after they came; other: not Femi's; Femi's own reply never counts.
        assertThat(threads.unreadCount()).isEqualTo(2);
    }
}
