package com.hospio.tenant.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.BusinessCreatedEvent;
import com.hospio.common.kafka.events.BusinessStageChangedEvent;
import com.hospio.common.kafka.events.HotelCreatedEvent;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.BusinessSummary;
import com.hospio.tenant.dto.CreateBusinessRequest;
import com.hospio.tenant.dto.CreateHotelRequest;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.dto.HotelStatusRequest;
import com.hospio.tenant.dto.InternalCreateBusinessRequest;
import com.hospio.tenant.dto.StageChangeRequest;
import com.hospio.tenant.dto.UpdateBusinessRequest;
import com.hospio.tenant.dto.UpdateHotelRequest;
import com.hospio.tenant.dto.UpdateMyBusinessRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Hotel;
import com.hospio.tenant.entity.OnboardingDraft;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Businesses and their hotels. Every stage change goes through {@link #moveStage} so
 * {@code BusinessStageChanged} is published whoever moved it; {@code hotel_count} is recounted on
 * every hotel added.
 */
@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businesses;
    private final HotelRepository hotels;
    private final OnboardingDraftRepository drafts;
    private final DraftData draftData;
    private final EventPublisher events;

    // ---- platform: list, read, create, edit ---------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<BusinessSummary> search(Business.Stage stage, String q, int page, int size) {
        requirePlatform();
        Specification<Business> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (stage != null) {
                ps.add(cb.equal(root.get("stage"), stage));
            }
            if (StringUtils.hasText(q)) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("ownerName")), like),
                        cb.like(cb.lower(root.get("ownerEmail")), like), cb.like(cb.lower(root.get("city")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Business> result = businesses.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("name")));
        return PagedResponse.of(result, this::toSummary);
    }

    @Transactional(readOnly = true)
    public BusinessResponse get(String id) {
        return toResponse(find(id));
    }

    /** Back-office onboarding step 1: the business and its draft at step 1, stage ONBOARDING. */
    @Transactional
    public BusinessResponse create(CreateBusinessRequest request) {
        requirePlatform();
        Business business = businesses.save(Business.builder()
                .name(request.name()).legalName(request.legalName()).registrationNumber(request.registrationNumber())
                .businessType(request.businessType()).ownerName(request.ownerName()).ownerEmail(request.ownerEmail().toLowerCase())
                .ownerPhone(request.ownerPhone()).feeModel(request.feeModel()).feeValue(request.feeValue())
                .currency(request.currency() == null ? "NGN" : request.currency()).country(request.country())
                .timezone(request.timezone() == null ? "Africa/Lagos" : request.timezone())
                .city(request.city()).source(request.source() == null ? Business.Source.BACK_OFFICE : request.source())
                .marketerId(request.marketerId()).accountManagerId(CurrentUser.require().getUserId())
                .stage(Business.Stage.ONBOARDING)
                .build());
        openDraft(business, CurrentUser.require().getUserId());
        publishCreated(business);
        return toResponse(business);
    }

    /** Sign-up from identity-service: the business with the id identity chose, source SELF_SERVE, draft at step 1. */
    @Transactional
    public BusinessResponse createInternal(InternalCreateBusinessRequest request) {
        requirePlatform();
        if (businesses.existsById(request.id())) {
            throw new BadRequestException("A business with that id already exists");
        }
        Business business = Business.builder()
                .name(request.name()).city(StringUtils.hasText(request.city()) ? request.city() : null)
                .ownerUserId(request.ownerUserId()).ownerName(request.ownerName()).ownerEmail(request.ownerEmail().toLowerCase())
                .ownerPhone(StringUtils.hasText(request.ownerPhone()) ? request.ownerPhone() : null)
                .source(request.source() == null ? Business.Source.SELF_SERVE : request.source())
                .stage(Business.Stage.ONBOARDING)
                .build();
        business.setId(request.id());
        business = businesses.save(business);
        openDraft(business, request.ownerUserId());
        publishCreated(business);
        return toResponse(business);
    }

    @Transactional
    public BusinessResponse update(String id, UpdateBusinessRequest r) {
        requirePlatform();
        Business b = find(id);
        b.setName(r.name());
        if (r.legalName() != null) b.setLegalName(r.legalName());
        if (r.registrationNumber() != null) b.setRegistrationNumber(r.registrationNumber());
        if (r.businessType() != null) b.setBusinessType(r.businessType());
        if (r.email() != null) b.setEmail(r.email());
        if (r.phone() != null) b.setPhone(r.phone());
        if (r.address() != null) b.setAddress(r.address());
        if (r.city() != null) b.setCity(r.city());
        if (r.logoUrl() != null) b.setLogoUrl(r.logoUrl());
        if (r.brandColour() != null) b.setBrandColour(r.brandColour());
        if (r.ownerName() != null) b.setOwnerName(r.ownerName());
        if (r.ownerEmail() != null) b.setOwnerEmail(r.ownerEmail().toLowerCase());
        if (r.ownerPhone() != null) b.setOwnerPhone(r.ownerPhone());
        if (r.feeModel() != null) b.setFeeModel(r.feeModel());
        if (r.feeValue() != null) b.setFeeValue(r.feeValue());
        if (r.currency() != null) b.setCurrency(r.currency());
        if (r.country() != null) b.setCountry(r.country());
        if (r.timezone() != null) b.setTimezone(r.timezone());
        if (r.contractStartsOn() != null) b.setContractStartsOn(r.contractStartsOn());
        if (r.contractNotes() != null) b.setContractNotes(r.contractNotes());
        if (r.marketerId() != null) b.setMarketerId(r.marketerId());
        if (r.accountManagerId() != null) b.setAccountManagerId(r.accountManagerId());
        if (r.source() != null) b.setSource(r.source());
        return toResponse(businesses.save(b));
    }

    @Transactional
    public BusinessResponse changeStage(String id, StageChangeRequest request) {
        requirePlatform();
        Business business = find(id);
        moveStage(business, request.stage(), request.reason());
        return toResponse(business);
    }

    @Transactional
    public BusinessResponse assignAccountManager(String id, String adminId) {
        requirePlatform();
        Business business = find(id);
        business.setAccountManagerId(adminId);
        return toResponse(businesses.save(business));
    }

    // ---- the owner's own business --------------------------------------------------------------------

    /** The caller's own business (owner and manager portals). */
    @Transactional(readOnly = true)
    public BusinessResponse mine() {
        return toResponse(find(TenantContext.requireBusiness()));
    }

    @Transactional
    public BusinessResponse updateMine(UpdateMyBusinessRequest r) {
        requireOwner();
        Business b = find(TenantContext.requireBusiness());
        b.setName(r.name());
        b.setLegalName(r.legalName());
        b.setRegistrationNumber(r.registrationNumber());
        b.setBusinessType(r.businessType());
        b.setEmail(r.email());
        b.setPhone(r.phone());
        b.setAddress(r.address());
        b.setCity(r.city());
        b.setLogoUrl(r.logoUrl());
        b.setBrandColour(r.brandColour());
        if (StringUtils.hasText(r.timezone())) {
            b.setTimezone(r.timezone());
        }
        return toResponse(businesses.save(b));
    }

    // ---- hotels --------------------------------------------------------------------------------------

    @Transactional
    public HotelResponse addHotel(CreateHotelRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        String businessId = caller.isPlatform() ? request.businessId() : TenantContext.requireBusiness();
        if (!StringUtils.hasText(businessId)) {
            throw new BadRequestException("Say which business the hotel belongs to (businessId)");
        }
        if (!caller.isPlatform() && !caller.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner can add a hotel");
        }
        Business business = find(businessId);
        Hotel hotel = hotels.save(Hotel.builder().businessId(businessId).name(request.name()).city(request.city())
                .address(request.address()).phone(request.phone()).email(request.email())
                .timezone(request.timezone() == null ? business.getTimezone() : request.timezone())
                .checkInTime(request.checkInTime() == null ? "14:00" : request.checkInTime())
                .checkOutTime(request.checkOutTime() == null ? "12:00" : request.checkOutTime())
                .starRating(request.starRating()).managerUserId(request.managerUserId()).build());
        business.setHotelCount(hotels.countByBusinessId(businessId));
        businesses.save(business);
        events.publish(KafkaTopics.HOTEL_CREATED, HotelCreatedEvent.builder()
                .businessId(businessId).hotelId(hotel.getId()).name(hotel.getName()).city(hotel.getCity()).build());
        return HotelResponse.of(hotel);
    }

    /** One hotel for its owner, a manager or staff member granted it, or the platform. */
    @Transactional(readOnly = true)
    public HotelResponse hotelForCaller(String hotelId) {
        Hotel hotel = findHotel(hotelId);
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !(hotel.getBusinessId().equals(caller.getBusinessId())
                && (caller.has(Role.OWNER) || caller.getHotelIds().contains(hotelId)))) {
            throw new ForbiddenException("That hotel is not yours");
        }
        return HotelResponse.of(hotel);
    }

    @Transactional
    public HotelResponse updateHotel(String hotelId, UpdateHotelRequest r) {
        Hotel h = findHotel(hotelId);
        HospioUserDetails caller = CurrentUser.require();
        boolean owner = caller.has(Role.OWNER) && h.getBusinessId().equals(caller.getBusinessId());
        boolean managerHere = caller.has(Role.MANAGER) && caller.getHotelIds().contains(hotelId);
        if (!caller.isPlatform() && !owner && !managerHere) {
            throw new ForbiddenException("Only the owner, this hotel's manager or H.M.S can edit it");
        }
        h.setName(r.name());
        if (r.city() != null) h.setCity(r.city());
        if (r.address() != null) h.setAddress(r.address());
        if (r.phone() != null) h.setPhone(r.phone());
        if (r.email() != null) h.setEmail(r.email());
        if (r.timezone() != null) h.setTimezone(r.timezone());
        if (r.checkInTime() != null) h.setCheckInTime(r.checkInTime());
        if (r.checkOutTime() != null) h.setCheckOutTime(r.checkOutTime());
        if (r.starRating() != null) h.setStarRating(r.starRating());
        if (r.managerUserId() != null) h.setManagerUserId(r.managerUserId());
        return HotelResponse.of(hotels.save(h));
    }

    @Transactional
    public HotelResponse setHotelStatus(String hotelId, HotelStatusRequest request) {
        Hotel h = findHotel(hotelId);
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !(caller.has(Role.OWNER) && h.getBusinessId().equals(caller.getBusinessId()))) {
            throw new ForbiddenException("Only the owner or H.M.S can change a hotel's status");
        }
        h.setStatus(request.status());
        h.setStatusReason(request.status() == Hotel.Status.ACTIVE ? null : request.reason());
        return HotelResponse.of(hotels.save(h));
    }

    @Transactional(readOnly = true)
    public List<HotelResponse> hotelsOf(String businessId) {
        return hotels.findByBusinessIdOrderByName(businessId).stream().map(HotelResponse::of).toList();
    }

    /** Service-to-service lookup, no caller check: the row-level policy already scopes it. */
    @Transactional(readOnly = true)
    public HotelResponse hotel(String hotelId) {
        return HotelResponse.of(findHotel(hotelId));
    }

    @Transactional
    public void setOwner(String businessId, String ownerUserId) {
        Business business = find(businessId);
        business.setOwnerUserId(ownerUserId);
        businesses.save(business);
    }

    @Transactional
    public void setRoomCount(String hotelId, int roomCount) {
        hotels.findById(hotelId).ifPresent(h -> {
            h.setRoomCount(roomCount);
            hotels.save(h);
        });
    }

    // ---- shared --------------------------------------------------------------------------------------

    /** The one place a stage changes: saves it, keeps the suspension fields in step and publishes the event. */
    void moveStage(Business business, Business.Stage to, String reason) {
        Business.Stage from = business.getStage();
        if (from == to) {
            return;
        }
        business.setStage(to);
        if (to == Business.Stage.SUSPENDED) {
            business.setSuspendedAt(Instant.now());
            business.setSuspensionReason(reason);
        } else if (from == Business.Stage.SUSPENDED) {
            business.setSuspendedAt(null);
            business.setSuspensionReason(null);
        }
        businesses.save(business);
        events.publish(KafkaTopics.BUSINESS_STAGE_CHANGED, BusinessStageChangedEvent.builder()
                .businessId(business.getId()).fromStage(from.name()).toStage(to.name()).build());
    }

    Business find(String id) {
        return businesses.findById(id).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    Hotel findHotel(String id) {
        return hotels.findById(id).orElseThrow(() -> new ResourceNotFoundException("Hotel not found"));
    }

    private void openDraft(Business business, String assignedTo) {
        if (drafts.findByBusinessId(business.getId()).isEmpty()) {
            drafts.save(OnboardingDraft.builder().businessId(business.getId()).step(1).assignedTo(assignedTo)
                    .data(draftData.empty()).build());
        }
    }

    private void publishCreated(Business business) {
        events.publish(KafkaTopics.BUSINESS_CREATED, BusinessCreatedEvent.builder()
                .businessId(business.getId()).name(business.getName()).stage(business.getStage().name())
                .ownerUserId(business.getOwnerUserId()).source(business.getSource().name()).build());
    }

    private static void requirePlatform() {
        if (!CurrentUser.require().isPlatform()) {
            throw new ForbiddenException("Only H.M.S staff can do this");
        }
    }

    private static void requireOwner() {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.has(Role.OWNER) || caller.isPlatform()) {
            throw new ForbiddenException("Only the owner can do this");
        }
    }

    BusinessResponse toResponse(Business b) {
        BusinessResponse.Onboarding onboarding = drafts.findByBusinessId(b.getId())
                .map(d -> new BusinessResponse.Onboarding(d.getStatus(), d.getStep(), draftData.completedSteps(d.getData())))
                .orElse(null);
        return new BusinessResponse(b.getId(), b.getName(), b.getLegalName(), b.getRegistrationNumber(), b.getBusinessType(),
                b.getSource(), b.getCity(), b.getAddress(), b.getEmail(), b.getPhone(), b.getLogoUrl(), b.getBrandColour(),
                b.getOwnerUserId(), b.getOwnerName(), b.getOwnerEmail(), b.getOwnerPhone(), b.getStage(), b.getAccountManagerId(),
                b.getMarketerId(), b.getFeeModel(), b.getFeeValue(), b.getCurrency(), b.getCountry(), b.getTimezone(),
                b.getContractStartsOn(), b.getContractNotes(), b.getHotelCount(), b.getSuspendedAt(), b.getSuspensionReason(),
                b.getCreatedAt(), hotelsOf(b.getId()), onboarding);
    }

    private BusinessSummary toSummary(Business b) {
        List<Hotel> hs = hotels.findByBusinessIdOrderByName(b.getId());
        int rooms = hs.stream().mapToInt(Hotel::getRoomCount).sum();
        return new BusinessSummary(b.getId(), b.getName(), b.getStage(), b.getSource(), b.getCity(), hs.size(), rooms,
                b.getOwnerName(), b.getOwnerEmail(), b.getAccountManagerId(), null, b.getMarketerId(), b.getFeeModel(), b.getFeeValue(),
                b.getCreatedAt(), null);
    }
}
