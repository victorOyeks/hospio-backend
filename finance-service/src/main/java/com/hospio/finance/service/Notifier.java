package com.hospio.finance.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.finance.client.PeerServices;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Messages to people finance acts on: the staff member whose leave was decided, the managers on a till variance, the owner's daily report. */
@Slf4j
@Component
@RequiredArgsConstructor
public class Notifier {

    private final PeerServices peers;
    private final DeliveryService deliveries;

    /** Emails (or texts) one person. Never throws; answers what echoes back to the caller when deliveries echo is on. */
    public List<Delivery> toUser(String userId, String subject, String text, Delivery.Kind kind, String ref) {
        return peers.contact(userId).map(c -> send(c.email(), c.phone(), subject, text, kind, ref)).orElse(List.of());
    }

    /** A person's name from identity, when it can be read. */
    public java.util.Optional<String> nameOf(String userId) {
        return userId == null ? java.util.Optional.empty() : peers.contact(userId).map(PeerServices.Contact::fullName);
    }

    public List<Delivery> toManagers(String hotelId, String subject, String text, Delivery.Kind kind, String ref) {
        List<Delivery> out = new ArrayList<>();
        for (PeerServices.Contact c : peers.withRole(hotelId, "MANAGER")) {
            out.addAll(send(c.email(), c.phone(), subject, text, kind, ref));
        }
        return out;
    }

    public List<Delivery> send(String email, String phone, String subject, String text, Delivery.Kind kind, String ref) {
        try {
            if (email != null && !email.isBlank()) {
                return deliveries.send(Delivery.email(email, subject, text, kind, ref));
            }
            if (phone != null && !phone.isBlank()) {
                return deliveries.send(Delivery.sms(phone, text, kind, ref));
            }
        } catch (RuntimeException e) {
            log.warn("Could not send {} {}: {}", kind, ref, e.getMessage());
        }
        return List.of();
    }
}
