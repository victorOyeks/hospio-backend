package com.hospio.tenant.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.BusinessCreatedEvent;
import com.hospio.common.kafka.events.BusinessStageChangedEvent;
import com.hospio.common.kafka.events.HotelCreatedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.CreateBusinessRequest;
import com.hospio.tenant.dto.CreateHotelRequest;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.dto.StageChangeRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Hotel;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businesses;
    private final HotelRepository hotels;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<BusinessResponse> list(Business.Stage stage) {
        requirePlatform();
        List<Business> list = stage == null ? businesses.findAllByOrderByName() : businesses.findByStageOrderByName(stage);
        return list.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BusinessResponse get(String id) {
        return toResponse(find(id));
    }

    /** The caller's own business (owner and manager portals). */
    @Transactional(readOnly = true)
    public BusinessResponse mine() {
        return toResponse(find(TenantContext.requireBusiness()));
    }

    @Transactional
    public BusinessResponse create(CreateBusinessRequest request) {
        requirePlatform();
        Business business = businesses.save(Business.builder()
                .name(request.name()).legalName(request.legalName()).registrationNumber(request.registrationNumber())
                .businessType(request.businessType()).ownerName(request.ownerName()).ownerEmail(request.ownerEmail().toLowerCase())
                .ownerPhone(request.ownerPhone()).feeModel(request.feeModel()).feeValue(request.feeValue())
                .currency(request.currency() == null ? "NGN" : request.currency()).country(request.country())
                .timezone(request.timezone() == null ? "Africa/Lagos" : request.timezone())
                .marketerId(request.marketerId()).accountManagerId(CurrentUser.require().getUserId())
                .stage(Business.Stage.ONBOARDING)
                .build());
        events.publish(KafkaTopics.BUSINESS_CREATED, BusinessCreatedEvent.builder()
                .businessId(business.getId()).name(business.getName()).stage(business.getStage().name()).build());
        return toResponse(business);
    }

    @Transactional
    public HotelResponse addHotel(CreateHotelRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        String businessId = caller.isPlatform() ? request.businessId() : TenantContext.requireBusiness();
        if (businessId == null) {
            throw new BadRequestException("Say which business the hotel belongs to");
        }
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only the owner can add a hotel");
        }
        Business business = find(businessId);
        Hotel hotel = hotels.save(Hotel.builder().businessId(businessId).name(request.name()).city(request.city())
                .address(request.address()).phone(request.phone()).email(request.email())
                .timezone(request.timezone() == null ? business.getTimezone() : request.timezone()).build());
        events.publish(KafkaTopics.HOTEL_CREATED, HotelCreatedEvent.builder()
                .businessId(businessId).hotelId(hotel.getId()).name(hotel.getName()).city(hotel.getCity()).build());
        return HotelResponse.of(hotel);
    }

    @Transactional(readOnly = true)
    public List<HotelResponse> hotelsOf(String businessId) {
        return hotels.findByBusinessIdOrderByName(businessId).stream().map(HotelResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public HotelResponse hotel(String hotelId) {
        return HotelResponse.of(hotels.findById(hotelId).orElseThrow(() -> new ResourceNotFoundException("Hotel not found")));
    }

    @Transactional
    public BusinessResponse changeStage(String id, StageChangeRequest request) {
        requirePlatform();
        Business business = find(id);
        Business.Stage from = business.getStage();
        business.setStage(request.stage());
        if (request.stage() == Business.Stage.SUSPENDED) {
            business.setSuspendedAt(Instant.now());
            business.setSuspensionReason(request.reason());
        } else if (from == Business.Stage.SUSPENDED) {
            business.setSuspendedAt(null);
            business.setSuspensionReason(null);
        }
        businesses.save(business);
        events.publish(KafkaTopics.BUSINESS_STAGE_CHANGED, BusinessStageChangedEvent.builder()
                .businessId(id).fromStage(from.name()).toStage(request.stage().name()).build());
        return toResponse(business);
    }

    @Transactional
    public BusinessResponse assignAccountManager(String id, String adminId) {
        requirePlatform();
        Business business = find(id);
        business.setAccountManagerId(adminId);
        return toResponse(businesses.save(business));
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

    Business find(String id) {
        return businesses.findById(id).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    private static void requirePlatform() {
        if (!CurrentUser.require().isPlatform()) {
            throw new ForbiddenException("Only H.M.S staff can do this");
        }
    }

    BusinessResponse toResponse(Business b) {
        return new BusinessResponse(b.getId(), b.getName(), b.getLegalName(), b.getRegistrationNumber(), b.getOwnerUserId(),
                b.getOwnerName(), b.getOwnerEmail(), b.getOwnerPhone(), b.getStage(), b.getAccountManagerId(), b.getMarketerId(),
                b.getFeeModel(), b.getFeeValue(), b.getCurrency(), b.getTimezone(), b.getSuspendedAt(), b.getSuspensionReason(),
                b.getCreatedAt(), hotelsOf(b.getId()));
    }
}
