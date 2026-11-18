package com.hospio.tenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One wizard step's form, as the web apps send it with {@code PUT /onboarding/.../step} and read it back from the draft. Both
 * wizards share the step numbers, so this holds every field any step sends; a step fills only its own and the rest stay out of
 * the JSON (packages/api/src/types.ts OwnerOnboardingData / AdminOnboardingData, apps/admin onboarding/shared.tsx WizardData):
 * <ul>
 *   <li>business (both): name, legalName, registrationNumber, businessType, email, phone, address, city, country, timezone</li>
 *   <li>owner (back office 2): ownerName, ownerEmail, ownerPhone, contactName, contactEmail, contactPhone (the second contact)</li>
 *   <li>hotel (self-serve 2): hotelId, name, city</li>
 *   <li>fee (back office 3): feeModel, feeValue, currency, contractStartsOn, contractNotes, marketerId, feeAddendumRef</li>
 *   <li>rooms (self-serve 3): roomTypes, created; (back office 5): byHotel; outlets (self-serve 3 at a lounge, bar or restaurant)</li>
 *   <li>manager (self-serve 4): userId, fullName, email, temporaryPassword, inviteToken</li>
 *   <li>hotels (back office 4): hotelIds</li>
 *   <li>template (back office 6): templateId, appliedTo, picked, appliedAt</li>
 *   <li>people (back office 7): logins</li>
 *   <li>connections (back office 8): terminals, whatsappNumber, whatsappByHotel</li>
 *   <li>golive (back office 9): checks, notes, readyAt</li>
 *   <li>handover (back office 10): sentAt, ownerUserId, ownerEmail, inviteToken, temporaryPassword</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardingStepData(
        String name, String legalName, String registrationNumber, String businessType, String email, String phone, String address,
        String city, String country, String timezone,
        String ownerName, String ownerEmail, String ownerPhone, String contactName, String contactEmail, String contactPhone,
        String hotelId,
        String feeModel, BigDecimal feeValue, String currency, String contractStartsOn, String contractNotes, String marketerId,
        String feeAddendumRef,
        List<RoomTypeSummary> roomTypes, Integer created, Map<String, HotelRooms> byHotel,
        String userId, String fullName, String temporaryPassword, String inviteToken,
        List<String> hotelIds,
        String templateId, List<String> appliedTo, List<String> picked, Map<String, String> appliedAt,
        List<OnboardingLogin> logins,
        List<Terminal> terminals, String whatsappNumber, Map<String, String> whatsappByHotel,
        List<GoLiveCheck> checks, String notes, String readyAt,
        String sentAt, String ownerUserId,
        List<OutletSummary> outlets) {

    /** A step saved with no form ({@code {}}). */
    public static final OnboardingStepData EMPTY = new OnboardingStepData(null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);

    /** An outlet made in the self-serve wizard at a location without rooms (docs/plan-venue-type.md). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutletSummary(String outletId, String name, String kind) {
    }

    /** A room type made during onboarding: its id, name, nightly price and how many rooms of it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomTypeSummary(String roomTypeId, String name, BigDecimal price, Integer count) {
    }

    /** The rooms made in one hotel (back office: byHotel → hotel id → this). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HotelRooms(List<RoomTypeSummary> roomTypes, Integer created) {
    }

    /** A card terminal connected during onboarding. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Terminal(String hotelId, String terminalId, String name, String provider, String terminalRef) {
    }

    /** One line of the go-live checklist. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoLiveCheck(String key, String label, Boolean done, String detail) {
    }
}
