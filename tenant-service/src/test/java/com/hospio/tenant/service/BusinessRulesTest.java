package com.hospio.tenant.service;

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
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.dto.CreateBusinessRequest;
import com.hospio.tenant.dto.CreateHotelRequest;
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

/** The back-office onboarding field rules (DEF-028 to DEF-042): what a field may hold, and one business per name, CAC, email, phone. */
class BusinessRulesTest {

    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final HotelRepository hotels = mock(HotelRepository.class);
    private final OnboardingDraftRepository drafts = mock(OnboardingDraftRepository.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final BusinessService service = new BusinessService(businesses, hotels, drafts, new DraftData(new ObjectMapper()),
            mock(EventPublisher.class), identity);

    @BeforeEach
    void signIn() {
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(identity.taken(any(), any())).thenReturn(new IdentityClient.Taken(false, false));
        when(businesses.save(any())).thenAnswer(inv -> {
            Business b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId("new");
            }
            return b;
        });
        when(hotels.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CreateBusinessRequest create(String name, String owner, String email, String phone, String city,
                                                Business.FeeModel model, String fee) {
        return new CreateBusinessRequest(name, null, null, null, owner, email, phone, model, fee == null ? null : new BigDecimal(fee),
                null, null, null, null, city, null, null);
    }

    private static CreateBusinessRequest valid() {
        return create("Abuja Palms Hotels", "Musa Danladi", "musa@hospio-qa.test", "+234 803 555 0192", "Abuja",
                Business.FeeModel.PERCENT_OF_PAYMENTS, "1.5");
    }

    // ---- what a field may hold ------------------------------------------------------------------------

    @Test
    void namesNeedTwoLettersAndCitiesNoDigits() {
        assertThatThrownBy(() -> Fields.name("0000", "the business name", true)).hasMessage("Business name must contain letters, not only numbers or symbols");
        assertThatThrownBy(() -> Fields.name("@#$%", "the owner's full name", true)).hasMessageContaining("must contain letters");
        assertThatThrownBy(() -> Fields.name("   ", "the hotel name", true)).hasMessage("Enter the hotel name");
        assertThat(Fields.name("  Abuja   Palms ", "the hotel name", true)).isEqualTo("Abuja Palms");
        assertThat(Fields.name("", "the registered name", false)).isNull();
        assertThatThrownBy(() -> Fields.city("0000", true)).hasMessage("Enter a real city name");
        assertThatThrownBy(() -> Fields.city("Abuja 2", true)).hasMessage("Enter a real city name");
        assertThatThrownBy(() -> Fields.city("", true)).hasMessage("Enter the city");
        assertThat(Fields.city(" Port Harcourt ", true)).isEqualTo("Port Harcourt");
        assertThatThrownBy(() -> Fields.address("00000", false)).hasMessage("Enter a real street address");
        assertThat(Fields.address("Plot 41 Aminu Kano Crescent", false)).isEqualTo("Plot 41 Aminu Kano Crescent");
    }

    @Test
    void emailsNeedADomainWithADotAndPhonesTenToFifteenRealDigits() {
        assertThatThrownBy(() -> Fields.email("owner@", "the owner's email", true)).hasMessageContaining("valid email");
        assertThatThrownBy(() -> Fields.email("owner@hotel", "the owner's email", true)).hasMessageContaining("valid email");
        assertThat(Fields.email(" Musa@Hotel.NG ", "the owner's email", true)).isEqualTo("musa@hotel.ng");
        for (String bad : new String[]{"0000", "000000000", "abc123", "+234 000 000 0000"}) {
            assertThatThrownBy(() -> Fields.phone(bad, "the owner's phone", false)).as(bad).hasMessageContaining("real phone number");
        }
        assertThat(Fields.phone("+234  803 555 0192", "the owner's phone", false)).isEqualTo("+234 803 555 0192");
        assertThat(Fields.phoneKey("+234 803 555 0192")).isEqualTo(Fields.phoneKey("08035550192"));
    }

    @Test
    void cacNumbersAreRcBnOrItWithDigits() {
        assertThat(Fields.registrationNumber("rc-1873402")).isEqualTo("RC 1873402");
        assertThat(Fields.registrationNumber("1873402")).isEqualTo("RC 1873402");
        assertThat(Fields.registrationNumber("BN 3456789")).isEqualTo("BN 3456789");
        assertThat(Fields.registrationKey("RC 1873402")).isEqualTo(Fields.registrationKey("1873402"));
        for (String bad : new String[]{"0000", "000000000", "RC", "AB 12345", "RC 123456789", "abc"}) {
            assertThatThrownBy(() -> Fields.registrationNumber(bad)).as(bad).hasMessageContaining("CAC number");
        }
        assertThat(Fields.registrationNumber(" ")).isNull();
    }

    @Test
    void aPercentageIsZeroToOneHundredAndRequired() {
        Business.FeeModel pct = Business.FeeModel.PERCENT_OF_PAYMENTS;
        assertThat(Fields.fee(pct, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(Fields.fee(pct, new BigDecimal("100"))).isEqualByComparingTo("100");
        assertThatThrownBy(() -> Fields.fee(pct, new BigDecimal("-1"))).hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> Fields.fee(pct, new BigDecimal("100.1"))).hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> Fields.fee(pct, new BigDecimal("1.555"))).hasMessageContaining("two decimal places");
        assertThatThrownBy(() -> Fields.fee(pct, null)).hasMessage("Enter the share (%)");
        assertThatThrownBy(() -> Fields.fee(null, BigDecimal.ONE)).hasMessage("Choose the fee model");
        assertThatThrownBy(() -> Fields.fee(Business.FeeModel.PER_ROOM_NIGHT, new BigDecimal("-5"))).hasMessageContaining("₦0 and ₦1,000,000");
        assertThat(Fields.fee(Business.FeeModel.PER_ROOM_NIGHT, new BigDecimal("1500"))).isEqualByComparingTo("1500");
    }

    @Test
    void timesAreRealClockTimes() {
        assertThat(Fields.time(null, "check-in", "14:00")).isEqualTo("14:00");
        assertThatThrownBy(() -> Fields.time("", "check-in", "14:00")).hasMessage("Enter the check-in time, like 14:00");
        assertThatThrownBy(() -> Fields.time("25:00", "check-out", "12:00")).hasMessageContaining("check-out");
    }

    @Test
    void theSecondContactIsCheckedOnlyWhenFilled() throws Exception {
        ObjectMapper om = new ObjectMapper();
        OnboardingService.checkSecondContact(om.readTree("{\"contactName\":\"\",\"contactEmail\":\"\"}"));
        assertThatThrownBy(() -> OnboardingService.checkSecondContact(om.readTree("{\"contactEmail\":\"hauwa@\"}")))
                .hasMessageContaining("econd contact's email");
        assertThatThrownBy(() -> OnboardingService.checkSecondContact(om.readTree("{\"contactPhone\":\"0000\"}")))
                .hasMessageContaining("econd contact's phone");
    }

    // ---- creating and editing a business ---------------------------------------------------------------

    @Test
    void meaninglessValuesAreRefusedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.create(create("0000", "Musa Danladi", "m@hospio-qa.test", null, "Abuja", Business.FeeModel.PER_ROOM_NIGHT, "1500")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Business name");
        assertThatThrownBy(() -> service.create(create("Abuja Palms", "0000", "m@hospio-qa.test", null, "Abuja", Business.FeeModel.PER_ROOM_NIGHT, "1500")))
                .hasMessageContaining("Owner's full name");
        assertThatThrownBy(() -> service.create(create("Abuja Palms", "Musa", "m@hospio-qa.test", "0000", "Abuja", Business.FeeModel.PER_ROOM_NIGHT, "1500")))
                .hasMessageContaining("Owner's phone");
        assertThatThrownBy(() -> service.create(create("Abuja Palms", "Musa", "m@hospio-qa.test", null, "0000", Business.FeeModel.PER_ROOM_NIGHT, "1500")))
                .hasMessage("Enter a real city name");
        assertThatThrownBy(() -> service.create(create("Abuja Palms", "Musa", "m@hospio-qa.test", null, "Abuja", Business.FeeModel.PERCENT_OF_PAYMENTS, null)))
                .hasMessage("Enter the share (%)");
        verify(businesses, never()).save(any());
    }

    @Test
    void aBusinessNameEmailOrPhoneAlreadyUsedIsRefused() {
        when(businesses.otherWithName("", "abuja palms hotels")).thenReturn(Optional.of("Abuja Palms Hotels"));
        assertThatThrownBy(() -> service.create(valid())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("A business called Abuja Palms Hotels already exists");

        when(businesses.otherWithName(anyString(), anyString())).thenReturn(Optional.empty());
        when(businesses.otherWithOwnerEmail("", "musa@hospio-qa.test")).thenReturn(Optional.of("Smiles fc"));
        assertThatThrownBy(() -> service.create(valid())).hasMessage("musa@hospio-qa.test already has an account. Use a different email for this owner");

        when(businesses.otherWithOwnerEmail(anyString(), anyString())).thenReturn(Optional.empty());
        when(businesses.otherWithOwnerPhone("", "8035550192")).thenReturn(Optional.of("Smiles fc"));
        assertThatThrownBy(() -> service.create(valid())).hasMessageContaining("phone number is already in use");

        when(businesses.otherWithOwnerPhone(anyString(), anyString())).thenReturn(Optional.empty());
        when(identity.taken("musa@hospio-qa.test", "+234 803 555 0192")).thenReturn(new IdentityClient.Taken(true, false));
        assertThatThrownBy(() -> service.create(valid())).hasMessageContaining("already has an account");

        when(identity.taken(any(), any())).thenReturn(new IdentityClient.Taken(false, false));
        assertThat(service.create(valid()).name()).isEqualTo("Abuja Palms Hotels");
    }

    @Test
    void aCacNumberUsedByAnotherBusinessIsRefusedHoweverItIsWritten() {
        Business mine = Business.builder().name("Abuja Palms").ownerEmail("m@hospio-qa.test").city("Abuja").build();
        mine.setId("b1");
        when(businesses.findById("b1")).thenReturn(Optional.of(mine));
        when(businesses.otherWithRegistration("b1", "RC1873402", "1873402")).thenReturn(Optional.of("Smiles fc"));
        UpdateBusinessRequest r = new UpdateBusinessRequest(null, null, "rc 1873402", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.update("b1", r)).isInstanceOf(ConflictException.class)
                .hasMessage("CAC number RC 1873402 is already used by Smiles fc. Check the number on the certificate");
    }

    @Test
    void anUnchangedValueIsNotCheckedAgainSoAnOldDuplicateNeverBlocksAStep() {
        Business mine = Business.builder().name("Smiles fc").ownerEmail("m@hospio-qa.test").city("Abuja")
                .feeModel(Business.FeeModel.PERCENT_OF_PAYMENTS).feeValue(BigDecimal.ONE).build();
        mine.setId("b1");
        when(businesses.findById("b1")).thenReturn(Optional.of(mine));
        when(businesses.otherWithName(anyString(), anyString())).thenReturn(Optional.of("SMILES FC"));
        UpdateBusinessRequest r = new UpdateBusinessRequest("Smiles  FC", null, null, null, null, null, null, "Abuja", null, null, null,
                "M@hospio-qa.test", null, null, new BigDecimal("2"), null, null, null, null, null, null, null, null);
        assertThat(service.update("b1", r).feeValue()).isEqualByComparingTo("2");
        verify(businesses, never()).otherWithName(anyString(), anyString());
        verify(identity, never()).taken(eq("m@hospio-qa.test"), isNull());
    }

    @Test
    void aHotelNeedsANameAndCityAndOneNamePerBusiness() {
        Business b = Business.builder().name("Abuja Palms").timezone("Africa/Lagos").build();
        b.setId("b1");
        when(businesses.findById("b1")).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.addHotel(new CreateHotelRequest("", "", null, null, null, null, null, null, null, null, "b1")))
                .hasMessage("Enter the hotel name");
        assertThatThrownBy(() -> service.addHotel(new CreateHotelRequest("000000", "00000", "00000", "0000", "wuse@", null, null, null, null, null, "b1")))
                .hasMessageContaining("Hotel name must contain letters");
        assertThatThrownBy(() -> service.addHotel(new CreateHotelRequest("Abuja Palms Wuse", "Abuja", null, "0000", null, null, null, null, null, null, "b1")))
                .hasMessageContaining("Hotel phone");
        assertThatThrownBy(() -> service.addHotel(new CreateHotelRequest("Abuja Palms Wuse", "Abuja", null, null, null, null, "", null, null, null, "b1")))
                .hasMessage("Enter the check-in time, like 14:00");
        when(hotels.otherWithName("b1", "", "abuja palms wuse")).thenReturn(Optional.of("Abuja Palms Wuse"));
        assertThatThrownBy(() -> service.addHotel(new CreateHotelRequest(" abuja  PALMS wuse", "Abuja", null, null, null, null, null, null, null, null, "b1")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already has a hotel called Abuja Palms Wuse");
    }
}
