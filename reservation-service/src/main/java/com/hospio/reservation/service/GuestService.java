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
        String q = query == null ? "" : query.trim();
        String d = GuestRules.digits(q);
        // From the 10th-last digit: "08031110001" and "2348031110001" both look for "8031110001".
        String digits = d.length() < 6 ? "" : d.length() > 10 ? d.substring(d.length() - 10) : d.replaceFirst("^0", "");
        return guests.search(TenantContext.requireBusiness(), q, digits);
    }

    @Transactional(readOnly = true)
    public Guest get(String id) {
        return guests.findById(id).orElseThrow(() -> new ResourceNotFoundException("Guest not found"));
    }

    /** Find the guest by phone first so a returning guest is never duplicated. */
    @Transactional
    public Guest findOrCreate(GuestRequest request) {
        String name = GuestRules.name(request.fullName());
        String phone = GuestRules.phone(request.phone());
        // "+234 803 111 0001", "08031110001" and "+2348031110001" are one person: match on the last ten digits.
        Guest existing = guests.findByPhoneKey(TenantContext.requireBusiness(), GuestRules.phoneKey(phone)).orElse(null);
        if (existing != null) {
            return existing;
        }
        Guest g = Guest.builder().fullName(name).phone(phone).email(request.email()).idType(request.idType())
                .idNumber(request.idNumber()).nationality(request.nationality()).preferences(request.preferences()).notes(request.notes()).build();
        g.setHotelId(null);
        return guests.save(g);
    }

    @Transactional
    public Guest update(String id, GuestRequest request) {
        Guest g = get(id);
        g.setFullName(GuestRules.name(request.fullName()));
        // A phone is kept optional here (older guests have none), but one that is given must be real.
        g.setPhone(request.phone() == null || request.phone().isBlank() ? null : GuestRules.phone(request.phone()));
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
