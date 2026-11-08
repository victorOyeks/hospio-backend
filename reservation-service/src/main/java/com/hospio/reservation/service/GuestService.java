package com.hospio.reservation.service;

import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.dto.GuestRequest;
import com.hospio.reservation.entity.Guest;
import com.hospio.reservation.repository.GuestRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestService {

    private final GuestRepository guests;

    @Transactional(readOnly = true)
    public List<Guest> search(String query) {
        return guests.search(TenantContext.requireBusiness(), query == null ? "" : query.trim());
    }

    @Transactional(readOnly = true)
    public Guest get(String id) {
        return guests.findById(id).orElseThrow(() -> new ResourceNotFoundException("Guest not found"));
    }

    /** Find the guest by phone first so a returning guest is never duplicated. */
    @Transactional
    public Guest findOrCreate(GuestRequest request) {
        if (request.phone() != null && !request.phone().isBlank()) {
            Guest existing = guests.findFirstByBusinessIdAndPhone(TenantContext.requireBusiness(), request.phone().trim()).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        Guest g = Guest.builder().fullName(request.fullName()).phone(request.phone()).email(request.email()).idType(request.idType())
                .idNumber(request.idNumber()).nationality(request.nationality()).preferences(request.preferences()).notes(request.notes()).build();
        g.setHotelId(null);
        return guests.save(g);
    }

    @Transactional
    public Guest update(String id, GuestRequest request) {
        Guest g = get(id);
        g.setFullName(request.fullName());
        g.setPhone(request.phone());
        g.setEmail(request.email());
        g.setIdType(request.idType());
        g.setIdNumber(request.idNumber());
        g.setNationality(request.nationality());
        g.setPreferences(request.preferences());
        g.setNotes(request.notes());
        return guests.save(g);
    }

    @Transactional
    public void countStay(String guestId) {
        guests.findById(guestId).ifPresent(g -> {
            g.setStays(g.getStays() + 1);
            guests.save(g);
        });
    }
}
