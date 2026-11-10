package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.kafka.events.SharedDeviceEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.SharedDeviceDtos;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.SharedDevice;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.SharedDeviceRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Shared devices (docs/shared-devices.md): a tablet set up at one hotel for some roles by someone with Full access there, a
 * manager or the owner. The device token it gets grants nothing by itself: it shows the lock screen (the hotel's people with
 * a PIN and a role that fits) and lets them unlock with their PIN ({@link AuthService#signInWithPin}). Managers and the owner
 * rename a device, change its roles or auto-lock time, or remove it, which ends every session opened on it.
 */
@Service
@RequiredArgsConstructor
public class SharedDeviceService {

    /** Roles a device can be for: every hotel role but the owner. */
    static final Set<Role> DEVICE_ROLES = EnumSet.of(Role.MANAGER, Role.FRONT_OFFICE, Role.HOUSEKEEPING, Role.LAUNDRY, Role.MAINTENANCE,
            Role.KITCHEN, Role.BAR, Role.INVENTORY, Role.FINANCE);

    private final SharedDeviceRepository devices;
    private final UserAccountRepository users;
    private final RoleGrantRepository grants;
    private final RefreshTokenRepository refreshTokens;
    private final TenantClient tenantClient;
    private final EventPublisher events;

    // ---- set up and manage (signed in) -----------------------------------------------------------

    @Transactional
    public SharedDeviceDtos.SetupResult setup(SharedDeviceDtos.Setup request) {
        HospioUserDetails caller = CurrentUser.require();
        if (caller.isPlatform() || caller.getActedBy() != null || caller.getBusinessId() == null) {
            throw new ForbiddenException("Only staff of the hotel can set up a shared device");
        }
        String hotelId = request.hotelId();
        if (!caller.getHotelIds().contains(hotelId)) {
            throw new ForbiddenException("That hotel is not one of yours");
        }
        Map<Role, AccessLevel> mine = callerRolesAt(caller, hotelId);
        boolean managerOrOwner = caller.has(Role.OWNER) || mine.containsKey(Role.MANAGER);
        if (!managerOrOwner && !mine.containsValue(AccessLevel.FULL)) {
            throw new ForbiddenException("Only someone with Full access, a manager or the owner can set up a shared device");
        }
        Set<Role> roles = parseRoles(request.roles());
        if (!managerOrOwner) {
            for (Role r : roles) {
                if (mine.get(r) != AccessLevel.FULL) {
                    throw new ForbiddenException("You can set up a device for the roles you have Full access to. Ask your manager for "
                            + label(r));
                }
            }
        }
        String raw = Hashing.randomToken();
        String hotelName = tenantClient.hotel(hotelId).map(TenantClient.HotelInfo::name).orElse(null);
        SharedDevice device = SharedDevice.builder()
                .businessId(caller.getBusinessId()).hotelId(hotelId).hotelName(hotelName)
                .name(cleanName(request.name())).tokenHash(Hashing.sha256(raw))
                .autoLockMinutes(request.autoLockMinutes() == null ? 5 : request.autoLockMinutes())
                .createdBy(caller.getUserId()).createdByName(caller.getFullName())
                .build();
        device.setRoleSet(roles);
        // Checked above against the caller's own grants; saved as the system so a hotel header other than the device's can't refuse it.
        SharedDevice saved = TenantContext.runWith(Tenant.system("device-setup"), () -> devices.save(device));
        audit(saved, "SET_UP", null, String.join(", ", roles.stream().map(SharedDeviceService::label).toList()));
        return new SharedDeviceDtos.SetupResult(view(saved), raw);
    }

    @Transactional(readOnly = true)
    public List<SharedDeviceDtos.Device> list(String hotelId) {
        HospioUserDetails caller = CurrentUser.require();
        String hotel = StringUtils.hasText(hotelId) ? hotelId : TenantContext.current().map(Tenant::hotelId).orElse(null);
        if (hotel == null) {
            throw new BadRequestException("Pick a hotel");
        }
        requireManagerAt(caller, hotel);
        return devices.findByHotelIdAndRevokedAtIsNullOrderByNameAsc(hotel).stream().map(SharedDeviceService::view).toList();
    }

    @Transactional
    public SharedDeviceDtos.Device change(String id, SharedDeviceDtos.Change request) {
        HospioUserDetails caller = CurrentUser.require();
        SharedDevice device = managed(caller, id);
        List<String> what = new ArrayList<>();
        if (request.name() != null && !cleanName(request.name()).equals(device.getName())) {
            what.add("renamed from " + device.getName());
            device.setName(cleanName(request.name()));
        }
        if (request.autoLockMinutes() != null && request.autoLockMinutes() != device.getAutoLockMinutes()) {
            what.add("auto-lock " + request.autoLockMinutes() + " min");
            device.setAutoLockMinutes(request.autoLockMinutes());
        }
        if (request.roles() != null) {
            Set<Role> roles = parseRoles(request.roles());
            if (!roles.equals(device.roleSet())) {
                device.setRoleSet(roles);
                what.add("for " + String.join(", ", roles.stream().map(SharedDeviceService::label).toList()));
            }
        }
        if (what.isEmpty()) {
            throw new BadRequestException("Nothing to change");
        }
        SharedDevice saved = devices.save(device);
        audit(saved, "CHANGED", null, String.join("; ", what));
        return view(saved);
    }

    /** Remove the device: its token stops working and every session opened on it ends at its next refresh. */
    @Transactional
    public void revoke(String id) {
        HospioUserDetails caller = CurrentUser.require();
        SharedDevice device = managed(caller, id);
        Instant now = Instant.now();
        device.setRevokedAt(now);
        device.setRevokedByName(caller.getFullName());
        devices.save(device);
        TenantContext.runWith(Tenant.system("device-revoke"), () -> refreshTokens.findByDeviceIdAndRevokedAtIsNull(device.getId()).forEach(t -> {
            t.setRevokedAt(now);
            refreshTokens.save(t);
        }));
        audit(device, "REVOKED", null, null);
    }

    // ---- the device itself (device token, nobody signed in) --------------------------------------

    /** The device for a token, if it is still set up. Runs as the system: nobody is signed in yet. */
    public Optional<SharedDevice> findDevice(String deviceToken) {
        if (!StringUtils.hasText(deviceToken)) {
            return Optional.empty();
        }
        return TenantContext.runWith(Tenant.system("device"), () -> devices.findByTokenHash(Hashing.sha256(deviceToken.trim())));
    }

    public Optional<SharedDevice> findById(String id) {
        return TenantContext.runWith(Tenant.system("device"), () -> devices.findById(id));
    }

    /** 401 when the token is unknown or the device was removed: the web then sends the device to the normal sign-in page. */
    public SharedDevice requireDevice(String deviceToken) {
        SharedDevice device = findDevice(deviceToken)
                .orElseThrow(() -> new UnauthorizedException("This device is not set up as a shared device. Sign in with email"));
        if (device.isRevoked()) {
            throw new UnauthorizedException("This device was removed as a shared device. Sign in with email");
        }
        return device;
    }

    @Transactional(readOnly = true)
    public SharedDeviceDtos.LockScreen lockScreen(String deviceToken) {
        SharedDevice device = requireDevice(deviceToken);
        return TenantContext.runWith(Tenant.system("lock-screen"), () -> {
            Set<Role> fits = device.roleSet();
            Map<String, Set<Role>> byUser = new LinkedHashMap<>();
            Set<String> owners = new java.util.HashSet<>();
            for (RoleGrant g : grants.findByHotelId(device.getHotelId())) {
                if (g.getRole() == Role.OWNER) {
                    owners.add(g.getUserId());
                }
                if (g.isActive() && fits.contains(g.getRole())) {
                    byUser.computeIfAbsent(g.getUserId(), k -> new TreeSet<>()).add(g.getRole());
                }
            }
            Instant now = Instant.now();
            List<SharedDeviceDtos.Person> people = users.findAllById(byUser.keySet()).stream()
                    .filter(u -> device.getBusinessId().equals(u.getBusinessId()) && !owners.contains(u.getId()))
                    .filter(u -> u.getStatus() == UserAccount.Status.ACTIVE || u.pauseHasEnded(now))
                    .sorted(Comparator.comparing(UserAccount::getFullName, String.CASE_INSENSITIVE_ORDER))
                    .map(u -> new SharedDeviceDtos.Person(u.getId(), u.getFullName(), u.getJobTitle(),
                            byUser.get(u.getId()).stream().map(Enum::name).toList(),
                            u.getLockedUntil() != null && u.getLockedUntil().isAfter(now) ? u.getLockedUntil() : null,
                            u.getPinHash() != null, u.getPinHash() == null ? u.getEmail() : null))
                    .toList();
            return new SharedDeviceDtos.LockScreen(device.getId(), device.getName(), device.getHotelId(), device.getHotelName(),
                    device.roleSet().stream().map(Enum::name).toList(), device.getAutoLockMinutes(), people);
        });
    }

    /** True when the person has an active role at the device's hotel that the device is for. */
    public boolean fitsDevice(String userId, SharedDevice device) {
        Set<Role> fits = device.roleSet();
        return TenantContext.runWith(Tenant.system("device-fit"), () -> grants.findByUserId(userId)).stream()
                .anyMatch(g -> g.isActive() && device.getHotelId().equals(g.getHotelId()) && fits.contains(g.getRole()));
    }

    /** The last unlock: Hotel setup shows when and who. */
    void used(SharedDevice device, UserAccount user) {
        device.setLastUsedAt(Instant.now());
        device.setLastUserId(user.getId());
        device.setLastUserName(user.getFullName());
        devices.save(device);
    }

    /** One activity log line under the device's hotel; {@code user} is the person the PIN belongs to. */
    void audit(SharedDevice device, String action, UserAccount user, String detail) {
        audit(device, null, action, user, detail);
    }

    /** {@code hotelId} places a line with no device (a PIN set) under the hotel the person works in. */
    void audit(SharedDevice device, String hotelId, String action, UserAccount user, String detail) {
        SharedDeviceEvent.SharedDeviceEventBuilder<?, ?> e = SharedDeviceEvent.builder().action(action).detail(detail).hotelId(hotelId);
        if (device != null) {
            e.deviceId(device.getId()).deviceName(device.getName()).businessId(device.getBusinessId()).hotelId(device.getHotelId());
        }
        if (user != null) {
            e.userId(user.getId()).userName(user.getFullName());
            if (device == null) {
                e.businessId(user.getBusinessId());
            }
        }
        // Named here, not from the tenant: these run as the system. Before anyone is signed in (wrong PINs, locks) the person
        // whose PIN it is stands as the actor; a support view shows the H.M.S name as everywhere else.
        Optional<HospioUserDetails> caller = CurrentUser.get();
        if (caller.isPresent()) {
            HospioUserDetails c = caller.get();
            e.actorId(c.getUserId()).actorName(c.getActedBy() == null ? c.getFullName() : "H.M.S \u00b7 " + c.getFullName());
        } else if (user != null) {
            e.actorId(user.getId()).actorName(user.getFullName());
        }
        events.publish(KafkaTopics.SHARED_DEVICE, e.build());
    }

    /** An in-app notice to the managers of the device's hotel. */
    void tellManagers(SharedDevice device, String title, String body) {
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("STAFF").title(title).body(body).link("/staff")
                .businessId(device.getBusinessId()).hotelId(device.getHotelId()).roles(List.of(Role.MANAGER.name())).build());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private SharedDevice managed(HospioUserDetails caller, String id) {
        SharedDevice device = devices.findById(id).filter(d -> !d.isRevoked())
                .orElseThrow(() -> new ResourceNotFoundException("That device was not found"));
        if (caller.getBusinessId() == null || !caller.getBusinessId().equals(device.getBusinessId())) {
            throw new ResourceNotFoundException("That device was not found");
        }
        requireManagerAt(caller, device.getHotelId());
        return device;
    }

    private void requireManagerAt(HospioUserDetails caller, String hotelId) {
        if (caller.isPlatform() || caller.getActedBy() != null || !caller.getHotelIds().contains(hotelId)) {
            throw new ForbiddenException("Only the hotel's managers and the owner manage its shared devices");
        }
        if (!caller.has(Role.OWNER) && !callerRolesAt(caller, hotelId).containsKey(Role.MANAGER)) {
            throw new ForbiddenException("Only the hotel's managers and the owner manage its shared devices");
        }
    }

    private Map<Role, AccessLevel> callerRolesAt(HospioUserDetails caller, String hotelId) {
        Map<Role, AccessLevel> roles = new java.util.EnumMap<>(Role.class);
        TenantContext.runWith(Tenant.system("device-caller"), () -> grants.findByUserId(caller.getUserId())).stream()
                .filter(g -> g.isActive() && hotelId.equals(g.getHotelId()))
                .forEach(g -> roles.merge(g.getRole(), g.getLevel(), (a, b) -> a.atLeast(b) ? a : b));
        return roles;
    }

    static Set<Role> parseRoles(List<String> names) {
        Set<Role> out = EnumSet.noneOf(Role.class);
        for (String n : names == null ? List.<String>of() : names) {
            Role r;
            try {
                r = Role.valueOf(n.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown role " + n);
            }
            if (!DEVICE_ROLES.contains(r)) {
                throw new BadRequestException("A shared device can't be for " + label(r) + ". Owners sign in with email and password");
            }
            out.add(r);
        }
        if (out.isEmpty()) {
            throw new BadRequestException("Pick at least one role for the device");
        }
        return out;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 40) {
            throw new BadRequestException("Name the device in 2 to 40 characters");
        }
        return n;
    }

    static String label(Role r) {
        String s = r.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static SharedDeviceDtos.Device view(SharedDevice d) {
        return new SharedDeviceDtos.Device(d.getId(), d.getName(), d.getHotelId(), d.getHotelName(),
                d.roleSet().stream().map(Enum::name).toList(), d.getAutoLockMinutes(), d.getLastUsedAt(), d.getLastUserName(),
                d.getCreatedByName(), d.getCreatedAt());
    }
}
