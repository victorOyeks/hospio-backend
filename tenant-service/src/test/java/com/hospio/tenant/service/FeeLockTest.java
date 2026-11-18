package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.billing.FeeLock;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.client.PlatformClient;
import com.hospio.tenant.dto.CreateBusinessRequest;
import com.hospio.tenant.dto.UpdateBusinessRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The fee lock: the platform default for everyone, another rate only with a signed addendum's reference, recorded in platform's change log. */
class FeeLockTest {

    private static final String LOCKED = "The platform fee is 1.5% for everyone. A different rate needs a signed addendum: enter its reference";

    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final PlatformClient platform = mock(PlatformClient.class);
    private final BusinessService service = new BusinessService(businesses, mock(HotelRepository.class), mock(OnboardingDraftRepository.class),
            new DraftData(new ObjectMapper()), mock(EventPublisher.class), identity, platform);

    @BeforeEach
    void signIn() {
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(identity.taken(any(), any())).thenReturn(new IdentityClient.Taken(false, false));
        when(platform.feeDefaultPct()).thenReturn(new BigDecimal("1.5"));
        when(businesses.save(any())).thenAnswer(inv -> {
            Business b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId("new");
            }
            return b;
        });
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CreateBusinessRequest create(String fee, String ref) {
        return new CreateBusinessRequest("Abuja Palms Hotels", null, null, null, "Musa Danladi", "musa@hospio-qa.test", null,
                Business.FeeModel.PERCENT_OF_SALES, new BigDecimal(fee), null, null, null, null, "Abuja", null, null, ref);
    }

    private static UpdateBusinessRequest feeEdit(String fee, String ref) {
        return new UpdateBusinessRequest(null, null, null, null, null, null, null, null, null, null, null, null, null,
                Business.FeeModel.PERCENT_OF_SALES, fee == null ? null : new BigDecimal(fee), null, null, null, null, null, null, null, null, ref);
    }

    private Business existing(String fee, String ref) {
        Business b = Business.builder().name("Abuja Palms").ownerEmail("m@hospio-qa.test").city("Abuja")
                .feeModel(Business.FeeModel.PERCENT_OF_SALES).feeValue(new BigDecimal(fee)).feeAddendumRef(ref).build();
        b.setId("b1");
        when(businesses.findById("b1")).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void theDefaultNeedsNoAddendumAndIsNotRecorded() {
        var created = service.create(create("1.50", "ignored"));
        assertThat(created.feeValue()).isEqualByComparingTo("1.5");
        assertThat(created.feeAddendumRef()).isNull();
        verify(platform, never()).recordFee(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherRateWithoutAnAddendumIsRefusedWithThePlatformDefaultInTheMessage() {
        assertThatThrownBy(() -> service.create(create("2", null))).isInstanceOf(BadRequestException.class).hasMessage(LOCKED);
        assertThatThrownBy(() -> service.create(create("1", "   "))).hasMessage(LOCKED);
        when(platform.feeDefaultPct()).thenReturn(new BigDecimal("2.00"));
        assertThatThrownBy(() -> service.create(create("1.5", null)))
                .hasMessage("The platform fee is 2% for everyone. A different rate needs a signed addendum: enter its reference");
        verify(businesses, never()).save(any());
    }

    @Test
    void anotherRateWithAnAddendumIsStoredAndRecorded() {
        var created = service.create(create("2", "  ADD-2026-014 signed 18 Nov "));
        assertThat(created.feeAddendumRef()).isEqualTo("ADD-2026-014 signed 18 Nov");
        verify(platform).recordFee(eq("new"), any(), eq("ADD-2026-014 signed 18 Nov"), isNull(), any(), eq("a1"), eq("Amoria"));
    }

    @Test
    void anAddendumReferenceIsAtMost120Characters() {
        assertThatThrownBy(() -> service.create(create("2", "A".repeat(121)))).hasMessageContaining("at most 120 characters");
    }

    @Test
    void editingToAnotherRateNeedsAnAddendum() {
        existing("1.5", null);
        assertThatThrownBy(() -> service.update("b1", feeEdit("2.25", null))).hasMessage(LOCKED);
        var updated = service.update("b1", feeEdit("2.25", "ADD-7"));
        assertThat(updated.feeAddendumRef()).isEqualTo("ADD-7");
        verify(platform).recordFee(eq("b1"), any(), eq("ADD-7"), any(), any(), eq("a1"), eq("Amoria"));
    }

    @Test
    void resendingTheSameFeeLeavesItAloneSoOtherEditsAreNotBlocked() {
        existing("2", "ADD-7");
        var updated = service.update("b1", feeEdit("2.00", null));
        assertThat(updated.feeAddendumRef()).isEqualTo("ADD-7");
        verify(platform, never()).recordFee(any(), any(), any(), any(), any(), any(), any());
        // An older rate from before the lock is not re-checked when it comes back unchanged.
        existing("2.5", null);
        assertThat(service.update("b1", feeEdit("2.5", null)).feeValue()).isEqualByComparingTo("2.5");
    }

    @Test
    void goingBackToTheDefaultClearsTheAddendumAndIsRecorded() {
        existing("2", "ADD-7");
        var updated = service.update("b1", feeEdit("1.5", null));
        assertThat(updated.feeAddendumRef()).isNull();
        verify(platform).recordFee(eq("b1"), any(), isNull(), any(), any(), anyString(), anyString());
    }

    @Test
    void aFeeOffTheDefaultIsNotSavedWhenItCannotBeRecorded() {
        existing("1.5", null);
        doThrow(new ExternalServiceException("down", null)).when(platform).recordFee(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.update("b1", feeEdit("3", "ADD-9"))).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void whenPlatformCannotBeAskedTheDefaultIsOnePointFive() {
        when(platform.feeDefaultPct()).thenReturn(null);
        assertThat(service.create(create("1.5", null)).feeAddendumRef()).isNull();
        assertThat(FeeLock.FALLBACK_PCT).isEqualByComparingTo("1.5");
    }

    @Test
    void platformsFeeRuleWriteBackStoresTheReferenceButNotAtTheDefault() {
        existing("1.5", null);
        assertThat(service.setFee("b1", Business.FeeModel.PERCENT_OF_SALES, new BigDecimal("2"), "ADD-1").feeAddendumRef()).isEqualTo("ADD-1");
        assertThat(service.setFee("b1", Business.FeeModel.PERCENT_OF_SALES, new BigDecimal("1.5"), "ADD-1").feeAddendumRef()).isNull();
    }
}
