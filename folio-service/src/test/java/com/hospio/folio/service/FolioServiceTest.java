package com.hospio.folio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.folio.config.ApprovalLimits;
import com.hospio.folio.dto.DiscountRequest;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.FolioLine;
import com.hospio.folio.repository.FolioLineRepository;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.PendingDiscountRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** The balance is charges + tax − discounts − payments; a replayed charge never posts twice; big discounts go to the owner. */
class FolioServiceTest {

    private final FolioRepository folios = mock(FolioRepository.class);
    private final FolioLineRepository lines = mock(FolioLineRepository.class);
    private final PendingDiscountRepository pending = mock(PendingDiscountRepository.class);
    private final ApprovalLimits limits = new ApprovalLimits();
    private final EventPublisher events = mock(EventPublisher.class);
    private final FolioService service = new FolioService(folios, lines, pending, limits, events);

    private final List<FolioLine> stored = new ArrayList<>();
    private Folio folio;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(limits, "managerDiscountLimit", new BigDecimal("50000"));
        folio = Folio.builder().guestName("Ifeoma").roomNumber("A302").build();
        folio.setId("f1");
        when(folios.findById("f1")).thenReturn(Optional.of(folio));
        when(folios.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lines.save(any())).thenAnswer(inv -> {
            FolioLine l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId("l" + (stored.size() + 1));
                stored.add(l);
            }
            return l;
        });
        when(lines.findByFolioIdOrderByPostedAt("f1")).thenAnswer(inv -> new ArrayList<>(stored));
        when(lines.findBySourceModuleAndSourceRef(any(), any())).thenAnswer(inv ->
                stored.stream().filter(l -> inv.getArgument(0).equals(l.getSourceModule()) && inv.getArgument(1).equals(l.getSourceRef())).findFirst());
        when(pending.save(any())).thenAnswer(inv -> inv.getArgument(0));
        signInAs(Role.MANAGER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void balanceFollowsChargesTaxDiscountsAndPayments() {
        service.postCharge(folio, "RESERVATION", "ROOM-b1", "Room A302 · 2 nights", new BigDecimal("90000"), BigDecimal.ZERO, "ACCOMMODATION", "u1", "Victor");
        service.postCharge(folio, "BAR", "ORDER-o1", "Bar · B-0001", new BigDecimal("13000"), new BigDecimal("650"), "BAR", "u2", "Segun");
        service.recordPayment(folio, "p1", new BigDecimal("45000"), "CARD", "Victor");
        service.applyDiscount(folio, new BigDecimal("3000"), "Loyal guest", "u3", "Tunde");

        assertThat(folio.getTotalCharges()).isEqualByComparingTo("103000.00");
        assertThat(folio.getTotalTax()).isEqualByComparingTo("650.00");
        assertThat(folio.getTotalPayments()).isEqualByComparingTo("45000.00");
        assertThat(folio.getTotalDiscounts()).isEqualByComparingTo("3000.00");
        assertThat(folio.getBalance()).isEqualByComparingTo("55650.00");
    }

    @Test
    void replayedChargeIsPostedOnce() {
        service.postCharge(folio, "BAR", "ORDER-o1", "Bar", new BigDecimal("13000"), BigDecimal.ZERO, "BAR", "u2", "Segun");
        service.postCharge(folio, "BAR", "ORDER-o1", "Bar", new BigDecimal("13000"), BigDecimal.ZERO, "BAR", "u2", "Segun");

        assertThat(stored).hasSize(1);
        assertThat(folio.getBalance()).isEqualByComparingTo("13000.00");
    }

    @Test
    void discountOverTheManagerLimitGoesToTheOwner() {
        service.postCharge(folio, "RESERVATION", "ROOM-b1", "Room", new BigDecimal("200000"), BigDecimal.ZERO, "ACCOMMODATION", "u1", "Victor");

        assertThatThrownBy(() -> service.discount("f1", new DiscountRequest(new BigDecimal("80000"), "Wedding party")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("goes to the owner");
        verify(events).publish(eq(KafkaTopics.APPROVAL_REQUESTED), any());
        assertThat(folio.getTotalDiscounts()).isEqualByComparingTo("0.00");
    }

    @Test
    void receptionistCannotDiscount() {
        signInAs(Role.FRONT_OFFICE);
        assertThatThrownBy(() -> service.discount("f1", new DiscountRequest(new BigDecimal("1000"), "x"))).isInstanceOf(ForbiddenException.class);
    }

    private void signInAs(Role role) {
        HospioUserDetails user = new HospioUserDetails("u1", "x@y.ng", "Tunde Bakare", "b1", List.of("h1"), Map.of(role, AccessLevel.FULL), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }
}
