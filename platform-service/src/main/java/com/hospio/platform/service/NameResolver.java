package com.hospio.platform.service;

import com.hospio.platform.client.IdentityClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** People's names for rows (admins, owners, openers), from identity's contacts lookup, cached for ten minutes. */
@Component
@RequiredArgsConstructor
public class NameResolver {

    private static final Duration TTL = Duration.ofMinutes(10);

    private record Cached(IdentityClient.Contact contact, Instant until) {
    }

    private final IdentityClient identity;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** id → full name for every id that resolved; ids identity does not know are left out. */
    public Map<String, String> names(Collection<String> userIds) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, IdentityClient.Contact> e : contacts(userIds).entrySet()) {
            if (e.getValue().fullName() != null) {
                out.put(e.getKey(), e.getValue().fullName());
            }
        }
        return out;
    }

    public Optional<String> name(String userId) {
        return contact(userId).map(IdentityClient.Contact::fullName);
    }

    public Optional<IdentityClient.Contact> contact(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(contacts(List.of(userId)).get(userId));
    }

    public Map<String, IdentityClient.Contact> contacts(Collection<String> userIds) {
        Map<String, IdentityClient.Contact> out = new HashMap<>();
        List<String> missing = new ArrayList<>();
        Instant now = Instant.now();
        if (userIds != null) {
            for (String id : userIds) {
                if (id == null || id.isBlank() || "system".equals(id)) {
                    continue;
                }
                Cached c = cache.get(id);
                if (c != null && c.until().isAfter(now)) {
                    out.put(id, c.contact());
                } else if (!missing.contains(id)) {
                    missing.add(id);
                }
            }
        }
        if (!missing.isEmpty()) {
            for (IdentityClient.Contact c : identity.contacts(missing)) {
                if (c.id() != null) {
                    cache.put(c.id(), new Cached(c, now.plus(TTL)));
                    out.put(c.id(), c);
                }
            }
        }
        return out;
    }
}
