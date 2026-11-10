package com.hospio.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.client.IdentityClient;
import com.hospio.messaging.dto.ThreadDtos;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.MessageRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Staff threads: who may write to whom, who sees a thread, and that the other side is told. */
class StaffThreadServiceTest {

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final StaffThreadService service = new StaffThreadService(conversations, messages, notifications, identity, deliveries);

    private HospioUserDetails godwin;

    @BeforeEach
    void setUp() {
        when(conversations.saveAndFlush(any(Conversation.class))).thenAnswer(i -> {
            Conversation c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId("c-" + System.nanoTime());
            }
            return c;
        });
        when(conversations.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(i -> i.getArgument(0));
        when(deliveries.send(any(List.class))).thenAnswer(i -> i.getArgument(0));
        when(identity.contact("tunde")).thenReturn(Optional.of(new IdentityClient.Contact("tunde", "Tunde Bakare", "t@x.ng", "+2348010000002", "b1", "ACTIVE", List.of("MANAGER"))));
        when(identity.contact("zed")).thenReturn(Optional.of(new IdentityClient.Contact("zed", "Zed Other", "z@x.ng", null, "b2", "ACTIVE", List.of("MANAGER"))));
        godwin = signInAs("godwin", "Godwin Kwin", Role.OWNER, "h1", "h2");
        TenantContext.set(Tenant.fromUser(godwin, "h1"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static HospioUserDetails signInAs(String id, String name, Role role, String... hotels) {
        HospioUserDetails u = new HospioUserDetails(id, id + "@x.ng", name, "b1", List.of(hotels), Map.of(role, AccessLevel.FULL),
                role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        return u;
    }

    @Test
    void ownerMessagesAManagerWhoIsToldAndGetsItOnWhatsApp() {
        ThreadDtos.ThreadSent sent = service.start(new ThreadDtos.NewThread("tunde", false, null, "Please send the diesel usage", true));

        assertThat(sent.thread().withName()).isEqualTo("Tunde Bakare");
        assertThat(sent.thread().mine()).isTrue();
        assertThat(sent.thread().hotelId()).isEqualTo("h1");
        assertThat(sent.thread().subject()).isEqualTo("Please send the diesel usage");
        verify(notifications).notify(isNull(), eq("tunde"), isNull(), eq("STAFF_MESSAGE"), eq("Message from Godwin Kwin"),
                eq("Please send the diesel usage"), anyString());
        assertThat(sent.deliveries()).hasSize(1);
        assertThat(sent.deliveries().get(0).to()).isEqualTo("+2348010000002");
    }

    @Test
    void notToSomeoneInAnotherBusinessNorToYourself() {
        assertThatThrownBy(() -> service.start(new ThreadDtos.NewThread("zed", false, null, "Hi", false)))
                .isInstanceOf(ForbiddenException.class).hasMessage("That person is not in your business");
        assertThatThrownBy(() -> service.start(new ThreadDtos.NewThread("godwin", false, null, "Hi", false)))
                .isInstanceOf(BadRequestException.class).hasMessage("You can't message yourself");
        verify(conversations, never()).saveAndFlush(any());
    }

    @Test
    void aHotelsManagersAsAGroupIsForTheOwnerOnlyAndNeedsAManager() {
        when(identity.withRole("h1", "MANAGER")).thenReturn(List.of());
        assertThatThrownBy(() -> service.start(new ThreadDtos.NewThread(null, true, null, "Hi", false)))
                .isInstanceOf(BadRequestException.class).hasMessage("This hotel has no manager to message yet");

        HospioUserDetails tunde = signInAs("tunde", "Tunde Bakare", Role.MANAGER, "h1");
        TenantContext.set(Tenant.fromUser(tunde, "h1"));
        assertThatThrownBy(() -> service.start(new ThreadDtos.NewThread(null, true, null, "Hi", false)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only the owner messages a hotel's managers as a group");
    }

    @Test
    void messageAllManagersStartsOneThreadPerManagerAcrossHotels() {
        IdentityClient.Contact tunde = new IdentityClient.Contact("tunde", "Tunde Bakare", null, null, "b1", "ACTIVE", List.of());
        IdentityClient.Contact amaka = new IdentityClient.Contact("amaka", "Amaka Eze", null, null, "b1", "ACTIVE", List.of());
        when(identity.withRole("h1", "MANAGER")).thenReturn(List.of(tunde));
        when(identity.withRole("h2", "MANAGER")).thenReturn(List.of(amaka, tunde));

        ThreadDtos.BroadcastSent sent = service.broadcastToManagers(new ThreadDtos.Broadcast("December rates", "Proposals by Friday", false));

        assertThat(sent.count()).isEqualTo(2);
        assertThat(sent.threads()).extracting(ThreadDtos.ThreadRow::withName).containsExactly("Tunde Bakare", "Amaka Eze");
        assertThat(sent.threads()).extracting(ThreadDtos.ThreadRow::hotelId).containsExactly("h1", "h2");
        assertThat(sent.threads().get(0).broadcastId()).isNotNull().isEqualTo(sent.threads().get(1).broadcastId());
        verify(notifications, times(2)).notify(isNull(), anyString(), isNull(), eq("STAFF_MESSAGE"), anyString(), anyString(), anyString());
    }

    @Test
    void staffCannotStartAndOutsidersCannotReadAThread() {
        HospioUserDetails victor = signInAs("victor", "Victor A", Role.FRONT_OFFICE, "h1");
        TenantContext.set(Tenant.fromUser(victor, "h1"));
        assertThatThrownBy(() -> service.start(new ThreadDtos.NewThread("tunde", false, null, "Hi", false)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only a manager or the owner can start a staff message");

        Conversation c = Conversation.builder().kind(Conversation.Kind.STAFF).audience("PERSON").createdBy("godwin").assignedTo("tunde").hotelId("h1").build();
        c.setId("c1");
        when(conversations.findById("c1")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.open("c1")).isInstanceOf(ForbiddenException.class).hasMessage("This conversation is between other people");
    }

    @Test
    void theRecipientRepliesAndTheStarterIsTold() {
        Conversation c = Conversation.builder().businessId("b1").kind(Conversation.Kind.STAFF).audience("PERSON").createdBy("godwin")
                .createdByName("Godwin Kwin").assignedTo("tunde").recipientName("Tunde Bakare").hotelId("h1").subject("Diesel").build();
        c.setId("c1");
        when(conversations.findById("c1")).thenReturn(Optional.of(c));
        signInAs("tunde", "Tunde Bakare", Role.MANAGER, "h1");

        ThreadDtos.ThreadSent sent = service.reply("c1", new ThreadDtos.Reply("Sent it this morning"));

        assertThat(sent.thread().mine()).isFalse();
        assertThat(sent.thread().withName()).isEqualTo("Godwin Kwin");
        assertThat(c.getLastPreview()).isEqualTo("Sent it this morning");
        assertThat(c.getRecipientReadAt()).isNotNull();
        ArgumentCaptor<String> who = ArgumentCaptor.forClass(String.class);
        verify(notifications).notify(isNull(), who.capture(), isNull(), eq("STAFF_MESSAGE"), eq("Message from Tunde Bakare"), anyString(), eq("/inbox?open=c1"));
        assertThat(who.getValue()).isEqualTo("godwin");
    }

    @Test
    void aSubjectDefaultsToTheStartOfTheMessage() {
        assertThat(StaffThreadService.subject(null, "  Short one ")).isEqualTo("Short one");
        assertThat(StaffThreadService.subject("", "x".repeat(80))).hasSize(58).endsWith("…");
    }
}
