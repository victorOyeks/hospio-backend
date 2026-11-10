package com.hospio.housekeeping.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.client.HotelConfigClient;
import com.hospio.housekeeping.dto.LostFoundView;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Housekeeping · Lost & found: log a found item (room or public area, who found it, a photo), tell the front office, return it to its
 * owner (who, the ID they showed, how, by whom) or dispose of it once the hotel's hold period (Hotel setup → Housekeeping,
 * lostFoundHoldDays, 90 by default) has passed. Only items still held (FOUND) can be returned or disposed of.
 */
@Slf4j
@Service
public class LostFoundService {

    static final long MAX_PHOTO = 2L * 1024 * 1024;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final LostFoundItemRepository items;
    private final EventPublisher events;
    private final ObjectStorage storage;
    private final HotelConfigClient hotelConfig;
    private final Clock clock;

    @Autowired
    public LostFoundService(LostFoundItemRepository items, EventPublisher events, ObjectStorage storage, HotelConfigClient hotelConfig) {
        this(items, events, storage, hotelConfig, Clock.systemUTC());
    }

    LostFoundService(LostFoundItemRepository items, EventPublisher events, ObjectStorage storage, HotelConfigClient hotelConfig, Clock clock) {
        this.items = items;
        this.events = events;
        this.storage = storage;
        this.hotelConfig = hotelConfig;
        this.clock = clock;
    }

    @Transactional
    public LostFoundView log(TaskRequests.LostFoundRequest r) {
        HospioUserDetails user = CurrentUser.require();
        String what = trim(r.description());
        if (what == null) {
            throw new BadRequestException("Say what was found");
        }
        String roomNumber = trim(r.roomNumber());
        String where = trim(r.foundWhere());
        if (where == null && trim(r.roomId()) != null && roomNumber != null) {
            where = "Room " + roomNumber;
        }
        if (where == null) {
            throw new BadRequestException("Say where it was found");
        }
        String finder = trim(r.foundByName());
        LostFoundItem item = items.save(LostFoundItem.builder().description(cut(what, 300)).details(cut(trim(r.details()), 500))
                .foundWhere(cut(where, 150)).roomId(trim(r.roomId())).roomNumber(cut(roomNumber, 20)).photoKey(trim(r.photoKey()))
                .foundBy(finder == null ? user.getUserId() : trim(r.foundById()))
                .foundByName(cut(finder == null ? user.getFullName() : finder, 150)).foundAt(Instant.now(clock)).build());
        if (!Boolean.FALSE.equals(r.tellFrontOffice())) {
            tell(item, user);
        }
        return view(item);
    }

    /** Tells the front office (in-app notice to the FRONT_OFFICE role at the hotel) about a held item, again if need be. */
    @Transactional
    public LostFoundView tellFrontOffice(String id) {
        LostFoundItem item = find(id);
        requireHeld(item);
        tell(item, CurrentUser.require());
        return view(item);
    }

    @Transactional
    public LostFoundView returnToOwner(String id, TaskRequests.ReturnItemRequest r) {
        HospioUserDetails user = CurrentUser.require();
        LostFoundItem item = find(id);
        requireHeld(item);
        String to = trim(r.returnedTo());
        if (to == null) {
            throw new BadRequestException("Say who it was returned to");
        }
        item.setStatus(LostFoundItem.Status.RETURNED);
        item.setReturnedTo(cut(to, 150));
        item.setReturnIdNote(cut(trim(r.idNote()), 200));
        item.setReturnHow(cut(trim(r.how()), 40));
        item.setReturnedBy(user.getUserId());
        item.setReturnedByName(user.getFullName());
        item.setReturnedAt(Instant.now(clock));
        return view(items.save(item));
    }

    @Transactional
    public LostFoundView dispose(String id, TaskRequests.DisposeItemRequest r) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isManagerOrOwner() && !user.isPlatform()
                && !user.has(com.hospio.common.enums.Role.HOUSEKEEPING, com.hospio.common.enums.AccessLevel.FULL)) {
            throw new ForbiddenException("Only a supervisor or manager can dispose of an item");
        }
        LostFoundItem item = find(id);
        requireHeld(item);
        String reason = r == null ? null : trim(r.reason());
        if (reason == null) {
            throw new BadRequestException("Say why it is being disposed of");
        }
        int days = hotelConfig.lostFoundHoldDays(item.getHotelId());
        LocalDate from = LostFoundView.disposableFrom(item.getFoundAt(), days);
        if (LocalDate.now(clock.withZone(LostFoundView.LAGOS)).isBefore(from)) {
            throw new BadRequestException("Items are kept " + days + " days; this one can be disposed of from " + from.format(DAY));
        }
        item.setStatus(LostFoundItem.Status.DISPOSED);
        item.setDisposeReason(cut(reason, 300));
        item.setDisposedByName(user.getFullName());
        item.setDisposedAt(Instant.now(clock));
        return view(items.save(item));
    }

    @Transactional
    public LostFoundView photo(String id, MultipartFile file) {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly()) {
            throw new ForbiddenException("Read-only access can't add a photo");
        }
        LostFoundItem item = find(id);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Pick a photo");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(type)) {
            throw new BadRequestException("The photo must be a JPEG, PNG or WebP image");
        }
        if (file.getSize() > MAX_PHOTO) {
            throw new BadRequestException("The photo is too big · keep it under 2 MB");
        }
        String key = "housekeeping/" + item.getBusinessId() + "/" + LocalDate.now(clock.withZone(LostFoundView.LAGOS)).getYear() + "/"
                + item.getId() + "." + type.substring(6);
        try {
            storage.put(key, file.getBytes(), type);
        } catch (Exception e) {
            log.warn("Could not store the photo for lost & found item {}: {}", item.getId(), e.getMessage());
            throw new BadRequestException("Couldn't save the photo right now · try again");
        }
        item.setPhotoKey(key);
        return view(items.save(item));
    }

    public record Photo(byte[] bytes, String contentType) {
    }

    @Transactional(readOnly = true)
    public Photo photo(String id) {
        LostFoundItem item = find(id);
        if (item.getPhotoKey() == null) {
            throw new ResourceNotFoundException("No photo of " + item.getDescription());
        }
        byte[] bytes = storage.get(item.getPhotoKey()).orElseThrow(() -> new ResourceNotFoundException("The photo of " + item.getDescription() + " is missing"));
        String key = item.getPhotoKey();
        return new Photo(bytes, key.endsWith(".png") ? "image/png" : key.endsWith(".webp") ? "image/webp" : "image/jpeg");
    }

    @Transactional(readOnly = true)
    public LostFoundView get(String id) {
        return view(find(id));
    }

    private void tell(LostFoundItem item, HospioUserDetails by) {
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("LOST_FOUND").title("Found in " + item.getFoundWhere() + ": " + item.getDescription())
                .body("Logged by " + (item.getFoundByName() == null ? by.getFullName() : item.getFoundByName()) + " · held at the housekeeping office")
                .link("/lost-found?open=" + item.getId()).roles(List.of("FRONT_OFFICE")).build());
        item.setFrontOfficeToldAt(Instant.now(clock));
        items.save(item);
    }

    private LostFoundView view(LostFoundItem item) {
        return LostFoundView.of(item, hotelConfig.lostFoundHoldDays(item.getHotelId()));
    }

    private LostFoundItem find(String id) {
        String hotel = TenantContext.requireHotel();
        return items.findById(id).filter(i -> hotel.equals(i.getHotelId()))
                .orElseThrow(() -> new ResourceNotFoundException("That item is not in this hotel's lost & found"));
    }

    private static void requireHeld(LostFoundItem item) {
        if (item.getStatus() == LostFoundItem.Status.RETURNED) {
            throw new BadRequestException(item.getDescription() + " was already returned");
        }
        if (item.getStatus() == LostFoundItem.Status.DISPOSED) {
            throw new BadRequestException(item.getDescription() + " was already disposed of");
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
