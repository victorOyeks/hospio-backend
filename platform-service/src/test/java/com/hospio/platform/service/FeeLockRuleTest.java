package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.billing.FeeLock;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.client.PaymentClient;
import com.hospio.platform.client.ReservationClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.PlatformRequests.FeeRuleRequest;
import com.hospio.platform.entity.FeeRule;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.FeePaymentRepository;
import com.hospio.platform.repository.FeeRuleRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.settings.FeeSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The fee lock on fee rules: the platform default (fees.revenuePct) for everyone, another rate only with a signed addendum, recorded. */
class FeeLockRuleTest {

    private static final String LOCKED = "The platform fee is 1.5% for everyone. A different rate needs a signed addendum: enter its reference";

    private final FeeRuleRepository rules = mock(FeeRuleRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final ChangeService changes = mock(ChangeService.class);
    private final FeeService service = new FeeService(rules, mock(FeeStatementRepository.class), mock(FeePaymentRepository.class),
            mock(MarketerRepository.class), mock(CommissionRepository.class), tenant, mock(PaymentClient.class), mock(ReservationClient.class),
            settings, mock(DeliveryService.class), new ObjectMapper(), null, changes);

    @BeforeEach
    void setUp() {
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(settings.fees()).thenReturn(FeeSettings.DEFAULTS);
        when(rules.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rules.findByBusinessIdOrderByStartsOnDesc("b1")).thenReturn(List.of());
        when(tenant.business("b1")).thenReturn(Optional.empty());
        when(tenant.setFee(anyString(), anyString(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static FeeRuleRequest rule(String value, String ref) {
        return new FeeRuleRequest("b1", "PERCENT_OF_SALES", new BigDecimal(value), LocalDate.of(2026, 11, 18), null, ref);
    }

    @Test
    void theLockItself() {
        BigDecimal pct = new BigDecimal("1.5");
        assertThat(FeeLock.addendumFor("PERCENT_OF_SALES", new BigDecimal("1.50"), "ADD-1", pct)).isNull();
        assertThat(FeeLock.addendumFor("PERCENT_OF_SALES", new BigDecimal("2"), " ADD-1 ", pct)).isEqualTo("ADD-1");
        assertThatThrownBy(() -> FeeLock.addendumFor("PERCENT_OF_SALES", new BigDecimal("0"), null, pct)).hasMessage(LOCKED);
        assertThatThrownBy(() -> FeeLock.addendumFor("PER_ROOM_NIGHT", new BigDecimal("1.5"), "", pct)).hasMessage(LOCKED);
        assertThatThrownBy(() -> FeeLock.addendumFor("PERCENT_OF_SALES", new BigDecimal("2"), "x".repeat(121), pct))
                .hasMessage("The addendum reference can be at most 120 characters");
        assertThat(FeeLock.describe(new BigDecimal("2.00"), "ADD-1", pct)).isEqualTo("Fee set to 2% of daily sales (addendum ADD-1)");
        assertThat(FeeLock.describe(pct, null, pct)).isEqualTo("Fee back to the platform default 1.5% of daily sales");
    }

    @Test
    void aRuleAtTheDefaultNeedsNothingAndIsNotRecorded() {
        FeeRule saved = service.saveRule(rule("1.5", null));
        assertThat(saved.getAddendumRef()).isNull();
        verify(changes, never()).recordFee(any(), any(), any(), any(), any(), any(), any());
        verify(tenant).setFee(eq("b1"), eq("PERCENT_OF_SALES"), any(), isNull());
    }

    @Test
    void aRuleAtAnotherRateWithoutAnAddendumIsRefused() {
        assertThatThrownBy(() -> service.saveRule(rule("2", " "))).isInstanceOf(BadRequestException.class).hasMessage(LOCKED);
        verify(rules, never()).save(any());
        verify(tenant, never()).setFee(any(), any(), any(), any());
    }

    @Test
    void theMessageUsesThePlatformDefaultFromSettings() {
        FeeSettings d = FeeSettings.DEFAULTS;
        when(settings.fees()).thenReturn(new FeeSettings(d.model(), d.perRoom(), new BigDecimal("1.75"), d.trialDays(), d.dueDay(), d.graceDays(),
                d.marketerSharePct(), d.marketerMonths(), d.whtPct(), d.providerFeePct(), d.hmsSharePct(), d.marketerMinPayout()));
        assertThatThrownBy(() -> service.saveRule(rule("1.5", null)))
                .hasMessage("The platform fee is 1.75% for everyone. A different rate needs a signed addendum: enter its reference");
        assertThat(service.saveRule(rule("1.75", null)).getAddendumRef()).isNull();
    }

    @Test
    void aRuleAtAnotherRateKeepsItsAddendumRecordsItAndPassesItToTenant() {
        FeeRule saved = service.saveRule(rule("2.25", "ADD-2026-014 signed 18 Nov"));
        assertThat(saved.getAddendumRef()).isEqualTo("ADD-2026-014 signed 18 Nov");
        verify(changes).recordFee(eq("b1"), any(), eq("ADD-2026-014 signed 18 Nov"), any(), isNull(), eq("a1"), eq("Amoria"));
        verify(tenant).setFee(eq("b1"), eq("PERCENT_OF_SALES"), any(), eq("ADD-2026-014 signed 18 Nov"));
    }

    @Test
    void goingBackToTheDefaultIsRecorded() {
        when(rules.findByBusinessIdOrderByStartsOnDesc("b1")).thenReturn(List.of(FeeRule.builder().businessId("b1").model("PERCENT_OF_SALES")
                .value(new BigDecimal("2")).addendumRef("ADD-1").startsOn(LocalDate.of(2026, 1, 1)).build()));
        service.saveRule(rule("1.5", null));
        verify(changes).recordFee(eq("b1"), any(), isNull(), any(), eq(new BigDecimal("2")), eq("a1"), eq("Amoria"));
        assertThat(FeeService.feeChangeToRecord(new BigDecimal("1.50"), null, new BigDecimal("1.5"))).isFalse();
        assertThat(FeeService.feeChangeToRecord(null, null, new BigDecimal("1.5"))).isFalse();
    }
}
