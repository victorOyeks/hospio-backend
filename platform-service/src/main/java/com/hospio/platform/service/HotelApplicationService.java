package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Phones;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.HotelApplicationDtos.ApplicationView;
import com.hospio.platform.dto.HotelApplicationDtos.ApplyRequest;
import com.hospio.platform.dto.HotelApplicationDtos.HotelApplicationEvent;
import com.hospio.platform.dto.HotelApplicationDtos.NoteView;
import com.hospio.platform.dto.HotelApplicationDtos.Permissions;
import com.hospio.platform.dto.HotelApplicationDtos.StatusRequest;
import com.hospio.platform.dto.HotelApplicationDtos.Submission;
import com.hospio.platform.entity.HotelApplication;
import com.hospio.platform.entity.HotelApplicationNote;
import com.hospio.platform.entity.MonthlySales;
import com.hospio.platform.repository.HotelApplicationNoteRepository;
import com.hospio.platform.repository.HotelApplicationRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * "Book a demo" (docs/onboarding.md, "Applications"): self sign-up is off, so a hotel, lounge, bar or restaurant asks for a demo
 * on the landing site and the team works the request in the admin portal's Hotel applications: call them (CONTACTED), meet them
 * (MEETING_BOOKED), agree terms (APPROVED), onboard the business with the back-office wizard and link it here (ONBOARDED), or
 * decline it. The public form is checked and cut to length like the partner form: hidden field, hourly limit per address and
 * email, and a repeat from an email with an open request becomes a note on that request (answered "received" like a new one).
 * Everyone on the platform may read; super admins and support change (the partner-application DECIDE rule).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelApplicationService {

    public static final String RECEIVED_MESSAGE = "Thanks — we've received your request. Our team will call you within 1 working day.";
    public static final String LIMITED_MESSAGE = "You've sent a few requests in a short time. Please try again in an hour, or email us at ";
    static final String ACTIVITY_TOPIC = KafkaTopics.PREFIX + "hotel-application";
    static final String NOTICE_KIND = "HOTEL_APPLICATION";
    static final String WEBSITE = "Website";

    static final List<String> TYPES = List.of("HOTEL", "LOUNGE", "BAR", "RESTAURANT", "MIXED");
    static final Map<String, String> TYPE_WORDS = Map.of("HOTEL", "Hotel", "LOUNGE", "Lounge", "BAR", "Bar", "RESTAURANT", "Restaurant",
            "MIXED", "Mixed (hotel with a lounge, bar or restaurant)");
    /** Types with rooms: the rooms question is shown and kept only for these. */
    static final Set<String> WITH_ROOMS = Set.of("HOTEL", "MIXED");
    static final Map<String, String> HEARD = new LinkedHashMap<>();
    static final Map<String, String> TIMES = new LinkedHashMap<>();

    static {
        HEARD.put("SEARCH", "Search (Google)");
        HEARD.put("SOCIAL", "Social media");
        HEARD.put("FRIEND", "A friend or another business");
        HEARD.put("MARKETER", "A Hospio360 partner");
        HEARD.put("EVENT", "An event");
        HEARD.put("OTHER", "Other");
        TIMES.put("MORNING", "Morning (9am–12pm)");
        TIMES.put("AFTERNOON", "Afternoon (12pm–4pm)");
        TIMES.put("EVENING", "Evening (4pm–6pm)");
        TIMES.put("ANYTIME", "Any time");
    }

    static final int MESSAGE_MAX = 1000;
    static final int NOTE_MAX = 2000;
    static final int MAX_ROOMS = 5000;
    /** Statuses an admin can pick; ONBOARDED comes from linking the business. */
    static final Set<String> PICKABLE = Set.of(HotelApplication.NEW, HotelApplication.CONTACTED, HotelApplication.MEETING_BOOKED,
            HotelApplication.APPROVED, HotelApplication.DECLINED);
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]{1,64}@[a-z0-9](?:[a-z0-9.-]{0,180}[a-z0-9])?\\.[a-z]{2,24}$");
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M} .'’-]{2,150}$");
    private static final Pattern BUSINESS = Pattern.compile("^[\\p{L}\\p{M}0-9 .,&'’()/@+-]{2,150}$");
    private static final Pattern PLACE = Pattern.compile("^[\\p{L}\\p{M}0-9 .,'’()/-]{2,100}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9-]{3,20}$");

    private final HotelApplicationRepository applications;
    private final HotelApplicationNoteRepository notes;
    private final MarketerService marketers;
    private final PlatformSettingsService settings;
    private final DeliveryService deliveries;
    private final HotelApplicationLimiter limiter;
    private final IdentityClient identity;
    private final TenantClient tenants;
    private final EventPublisher events;
    private final ActivityLogService activity;

    // ---- the public form -----------------------------------------------------------------------------

    /**
     * One request from the landing site. Not one transaction on purpose: the row is saved on its own, so a second request for the
     * same email arriving at the same moment (refused by the unique index) is answered "received" like any repeat.
     */
    public Submission submit(ApplyRequest r, String address, String userAgent) {
        if (r == null) {
            return new Submission(Submission.INVALID, Map.of("ownerName", "Enter your full name"));
        }
        if (StringUtils.hasText(r.website())) {
            log.info("Hotel application from {} dropped: the hidden field was filled", address);
            return Submission.received();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String ownerName = PartnerApplicationService.text(r.ownerName());
        if (ownerName == null || !NAME.matcher(ownerName).matches() || !Phones.meaningfulName(ownerName)) {
            errors.put("ownerName", ownerName == null ? "Enter your full name" : "Enter your name using letters only (up to 150 characters)");
        }
        String phone = PartnerApplicationService.nigerianPhone(r.phone());
        if (phone == null) {
            errors.put("phone", StringUtils.hasText(r.phone()) ? "Enter a Nigerian phone number, like 0803 555 0192 or +234 803 555 0192" : "Enter your phone number");
        }
        String email = r.email() == null ? "" : r.email().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            errors.put("email", email.isEmpty() ? "Enter your email address" : "Enter a real email address, like ada@example.com");
        }
        String businessName = PartnerApplicationService.text(r.businessName());
        if (businessName == null || !BUSINESS.matcher(businessName).matches() || !Phones.meaningfulName(businessName)) {
            errors.put("businessName", businessName == null ? "Enter your business name" : "Enter the business name (letters, up to 150 characters)");
        }
        String type = r.businessType() == null ? null : r.businessType().trim().toUpperCase(Locale.ROOT);
        if (type == null || !TYPES.contains(type)) {
            errors.put("businessType", "Choose what kind of business it is");
        }
        String city = PartnerApplicationService.text(r.city());
        if (city == null || !PLACE.matcher(city).matches()) {
            errors.put("city", city == null ? "Enter your city" : "Enter the name of your city");
        }
        String state = PartnerApplicationService.text(r.state());
        if (state == null || !PartnerApplicationService.STATES.contains(state)) {
            errors.put("state", "Choose your state");
        }
        Integer rooms = type != null && WITH_ROOMS.contains(type) ? r.rooms() : null;
        if (rooms != null && (rooms < 1 || rooms > MAX_ROOMS)) {
            errors.put("rooms", "Enter the number of rooms (1 to " + MAX_ROOMS + "), or leave it empty");
        }
        MonthlySales sales = MonthlySales.parse(r.monthlySales()).orElse(null);
        if (sales == null) {
            errors.put("monthlySales", "Choose your average monthly sales (an estimate is fine)");
        }
        String heard = r.heardFrom() == null ? null : r.heardFrom().trim().toUpperCase(Locale.ROOT);
        if (heard == null || !HEARD.containsKey(heard)) {
            errors.put("heardFrom", "Tell us how you heard about Hospio360");
        }
        String time = StringUtils.hasText(r.preferredTime()) ? r.preferredTime().trim().toUpperCase(Locale.ROOT) : null;
        if (time != null && !TIMES.containsKey(time)) {
            errors.put("preferredTime", "Choose a time of day, or leave it empty");
        }
        String message = PartnerApplicationService.note(r.message());
        if (message != null && message.length() > MESSAGE_MAX) {
            errors.put("message", "Keep the message to " + MESSAGE_MAX + " characters");
        }
        if (!errors.isEmpty()) {
            return new Submission(Submission.INVALID, errors);
        }
        if (!limiter.allowAddress(address) || !limiter.allowEmail(email)) {
            log.info("Hotel application from {} refused: over the hour's limit", address);
            return new Submission(Submission.LIMITED, Map.of());
        }
        String code = referralCode(r.referralCode());
        Optional<MarketerService.ReferralCodeInfo> marketer = code == null ? Optional.empty()
                : marketers.referralCode(code).filter(MarketerService.ReferralCodeInfo::canRefer);
        return TenantContext.runWith(Tenant.system("hotel-applications"), () -> {
            Optional<HotelApplication> open = applications.findFirstByEmailIgnoreCaseAndStatusNotIn(email,
                    List.of(HotelApplication.ONBOARDED, HotelApplication.DECLINED));
            if (open.isPresent()) {
                // Same answer as a new request (the form never says an email is known here); the team sees it on the open one.
                repeatNote(open.get(), businessName, type, phone, sales, message);
                log.info("Hotel application from {} added to the open request {} as a note", address, open.get().getId());
                return Submission.received();
            }
            HotelApplication a;
            try {
                a = applications.saveAndFlush(HotelApplication.builder().ownerName(ownerName).email(email).phone(phone)
                        .businessName(businessName).businessType(type).city(city).state(state).rooms(rooms).monthlySales(sales.name()).heardFrom(heard)
                        .preferredTime(time).message(message).referralCode(code)
                        .marketerProfileId(marketer.map(MarketerService.ReferralCodeInfo::profileId).orElse(null))
                        .marketerName(marketer.map(MarketerService.ReferralCodeInfo::fullName).orElse(null))
                        .status(HotelApplication.NEW).statusChangedAt(Instant.now())
                        .ip(cut(PartnerApplicationService.text(address), 64)).userAgent(cut(PartnerApplicationService.text(userAgent), 300)).build());
            } catch (DataIntegrityViolationException e) {
                log.info("Hotel application from {} not stored: another request for the same email got there first", address);
                return Submission.received();
            }
            log.info("Hotel application {} stored ({}, {}, {})", a.getId(), a.getBusinessType(), a.getCity(),
                    a.getMarketerProfileId() == null ? "no marketer" : "marketer " + a.getMarketerProfileId());
            deliveries.send(Delivery.email(email, "We received your Hospio360 demo request", receivedEmail(a), Delivery.Kind.NOTICE,
                    a.getId() + ":received"));
            deliveries.send(Delivery.email(supportEmail(), "New demo request: " + a.getBusinessName() + " (" + a.getCity() + ")", teamEmail(a),
                    Delivery.Kind.NOTICE, a.getId() + ":team"));
            tellStaff(a);
            return Submission.received();
        });
    }

    private void repeatNote(HotelApplication a, String businessName, String type, String phone, MonthlySales sales, String message) {
        try {
            StringBuilder b = new StringBuilder("Sent the Book a demo form again");
            if (!businessName.equalsIgnoreCase(a.getBusinessName())) {
                b.append(" · business: ").append(businessName);
            }
            if (!type.equals(a.getBusinessType())) {
                b.append(" · type: ").append(TYPE_WORDS.get(type));
            }
            if (!phone.equals(a.getPhone())) {
                b.append(" · phone: ").append(phone);
            }
            if (!sales.name().equals(a.getMonthlySales())) {
                b.append(" · monthly sales: ").append(sales.label());
            }
            if (message != null) {
                b.append("\n").append(message);
            }
            notes.save(HotelApplicationNote.builder().applicationId(a.getId()).kind(HotelApplicationNote.SYSTEM)
                    .body(cut(b.toString(), NOTE_MAX)).authorName(WEBSITE).build());
        } catch (RuntimeException e) {
            log.warn("Hotel application {}: the repeat was not noted ({})", a.getId(), e.getMessage());
        }
    }

    public String supportEmail() {
        try {
            String s = TenantContext.runWith(Tenant.system("hotel-applications"), () -> settings.general().supportEmail());
            return StringUtils.hasText(s) ? s : "hello@oyeksglobal.com";
        } catch (RuntimeException e) {
            return "hello@oyeksglobal.com";
        }
    }

    /** The bell of every super admin and support admin. Never fails the submission. */
    private void tellStaff(HotelApplication a) {
        try {
            Set<String> who = new LinkedHashSet<>();
            for (Map.Entry<String, String> s : staff().entrySet()) {
                who.add(s.getKey());
            }
            who.remove(null);
            if (who.isEmpty()) {
                log.warn("Hotel application {}: nobody to tell (no super admin or support admin found)", a.getId());
                return;
            }
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind(NOTICE_KIND).title("New demo request")
                    .body(a.getOwnerName() + " of " + a.getBusinessName() + " (" + TYPE_WORDS.get(a.getBusinessType()).split(" ")[0].toLowerCase(Locale.ROOT)
                            + ", " + a.getCity() + ") asked for a demo" + (a.getMarketerName() == null ? "" : ", referred by " + a.getMarketerName()) + ".")
                    .link("/dashboard/hotel-applications?app=" + a.getId()).userIds(new ArrayList<>(who)).build());
        } catch (RuntimeException e) {
            log.warn("Hotel application {}: the team was not told ({})", a.getId(), e.getMessage());
        }
    }

    /** Active super admins and support admins: id → name. */
    private Map<String, String> staff() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String role : List.of(Role.SUPER_ADMIN.name(), Role.SUPPORT.name())) {
            identity.platformStaff(role).forEach(c -> {
                if (c.id() != null && (c.status() == null || "ACTIVE".equalsIgnoreCase(c.status()))) {
                    out.putIfAbsent(c.id(), c.fullName());
                }
            });
        }
        return out;
    }

    // ---- the admin screen ----------------------------------------------------------------------------

    /** The list's orders: newest first (the default), or by monthly sales band, highest first, Prefer not / Not asked last. */
    public static final String SORT_NEWEST = "NEWEST";
    public static final String SORT_SALES = "SALES";
    static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));
    static final Sort BY_SALES = Sort.by(Sort.Direction.ASC, "monthlySalesRank").and(NEWEST);

    @Transactional(readOnly = true)
    public PageWithCounts<ApplicationView> list(String status, String q, String assigneeId, Integer page, Integer size) {
        return list(status, q, assigneeId, null, null, page, size);
    }

    /**
     * {@code monthlySales}: a band (UNDER_1M, FROM_1M_TO_5M, FROM_5M_TO_20M, ABOVE_20M, PREFER_NOT) or NOT_ASKED (sent before the
     * question); the counts follow it like the search. {@code sort}: NEWEST (default) or SALES.
     */
    @Transactional(readOnly = true)
    public PageWithCounts<ApplicationView> list(String status, String q, String assigneeId, String monthlySales, String sort, Integer page,
                                                Integer size) {
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (st != null && !"OPEN".equals(st) && !HotelApplication.STATUSES.contains(st)) {
            throw new BadRequestException("Unknown status " + st + ". Pick one of: OPEN, " + String.join(", ", HotelApplication.STATUSES));
        }
        String band = salesFilter(monthlySales);
        Sort order = order(sort);
        String who = StringUtils.hasText(assigneeId) ? assigneeId.trim() : null;
        Page<HotelApplication> result = applications.findAll(spec(st, q, who, band), Paging.of(page, size, order));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", applications.count(spec(null, q, who, band)));
        counts.put("OPEN", applications.count(spec("OPEN", q, who, band)));
        for (String s : HotelApplication.STATUSES) {
            counts.put(s, applications.count(spec(s, q, who, band)));
        }
        // The sidebar's badge: every NEW request, whatever the search says.
        counts.put("newTotal", applications.countByStatus(HotelApplication.NEW));
        return PageWithCounts.of(result, a -> ApplicationView.of(a, List.of()), counts, Map.of());
    }

    /** NEW requests, for the sidebar badge. */
    @Transactional(readOnly = true)
    public long newCount() {
        return applications.countByStatus(HotelApplication.NEW);
    }

    /** The band filter as a code, NOT_ASKED, or null for every band; anything else is refused. */
    static String salesFilter(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        if (MonthlySales.NOT_ASKED.equals(c)) {
            return c;
        }
        return MonthlySales.parse(c).map(Enum::name).orElseThrow(() -> new BadRequestException("Unknown monthly sales " + c
                + ". Pick one of: " + String.join(", ", java.util.Arrays.stream(MonthlySales.values()).map(Enum::name).toList()) + ", "
                + MonthlySales.NOT_ASKED));
    }

    static Sort order(String raw) {
        String c = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : SORT_NEWEST;
        return switch (c) {
            case SORT_NEWEST -> NEWEST;
            case SORT_SALES -> BY_SALES;
            default -> throw new BadRequestException("Unknown sort " + c + ". Pick one of: " + SORT_NEWEST + ", " + SORT_SALES);
        };
    }

    static Specification<HotelApplication> spec(String status, String q, String assigneeId) {
        return spec(status, q, assigneeId, null);
    }

    static Specification<HotelApplication> spec(String status, String q, String assigneeId, String monthlySales) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (MonthlySales.NOT_ASKED.equals(monthlySales)) {
                ps.add(cb.isNull(root.get("monthlySales")));
            } else if (monthlySales != null) {
                ps.add(cb.equal(root.get("monthlySales"), monthlySales));
            }
            if ("OPEN".equals(status)) {
                ps.add(cb.not(root.get("status").in(HotelApplication.ONBOARDED, HotelApplication.DECLINED)));
            } else if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (assigneeId != null) {
                ps.add(cb.equal(root.get("assigneeId"), assigneeId));
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(cb.like(cb.lower(root.get("ownerName")), like, '\\'), cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(root.get("phone"), like, '\\'), cb.like(cb.lower(root.get("businessName")), like, '\\'),
                        cb.like(cb.lower(root.get("city")), like, '\\'), cb.like(cb.lower(root.get("state")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("referralCode"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("marketerName"), "")), like, '\\')));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public ApplicationView get(String id) {
        return view(applications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Application not found")));
    }

    /** What the screen may offer the caller. */
    public Permissions permissions() {
        HospioUserDetails user = CurrentUser.require();
        return new Permissions(canUpdate(user), canDecline(user));
    }

    /** Support and super admins work the requests (the partner-application rule). */
    static boolean canUpdate(HospioUserDetails user) {
        return PartnerApplicationService.canDecide(user);
    }

    /** Declining follows the partner-application DECIDE rule: super admins and support. */
    static boolean canDecline(HospioUserDetails user) {
        return PartnerApplicationService.canDecide(user);
    }

    /**
     * Move the application to another status. Any open status may go to any other (forward, or back to correct a slip); MEETING_BOOKED
     * needs a meeting time; DECLINED needs a reason (kept, never emailed); a declined request may be reopened. ONBOARDED is set only
     * by linking the business, and is final.
     */
    @Transactional
    public ApplicationView changeStatus(String id, StatusRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (request == null || !StringUtils.hasText(request.status())) {
            throw new BadRequestException("Choose the new status");
        }
        String to = request.status().trim().toUpperCase(Locale.ROOT);
        if (HotelApplication.ONBOARDED.equals(to)) {
            throw new BadRequestException("An application becomes Onboarded when its business is linked: use Start onboarding");
        }
        if (!PICKABLE.contains(to)) {
            throw new BadRequestException("Unknown status " + to + ". Pick one of: NEW, CONTACTED, MEETING_BOOKED, APPROVED, DECLINED");
        }
        if (HotelApplication.DECLINED.equals(to) ? !canDecline(user) : !canUpdate(user)) {
            throw new ForbiddenException(HotelApplication.DECLINED.equals(to) ? "Only a super admin or support can decline an application"
                    : "Only a super admin or support can change an application");
        }
        HotelApplication a = locked(id);
        if (HotelApplication.ONBOARDED.equals(a.getStatus())) {
            throw new BadRequestException("This application is onboarded" + (a.getLinkedBusinessName() == null ? "" : " (" + a.getLinkedBusinessName() + ")")
                    + "; its status can't change");
        }
        if (to.equals(a.getStatus())) {
            throw new BadRequestException("The application is already " + label(to));
        }
        String reason = null;
        if (HotelApplication.DECLINED.equals(to)) {
            reason = PartnerApplicationService.note(request.reason());
            if (reason == null || reason.length() < 5) {
                throw new BadRequestException("Say why the application is declined (a few words; it is kept with the application)");
            }
            if (reason.length() > 500) {
                throw new BadRequestException("Keep the reason to 500 characters");
            }
        }
        if (HotelApplication.MEETING_BOOKED.equals(to)) {
            if (request.meetingAt() != null) {
                a.setMeetingAt(request.meetingAt());
            }
            if (a.getMeetingAt() == null) {
                throw new BadRequestException("Set the meeting date and time to mark the meeting booked");
            }
        }
        // Reopening a declined request clears the old reason.
        a.setDeclineReason(reason);
        String from = a.getStatus();
        a.setStatus(to);
        a.setStatusChangedAt(Instant.now());
        applications.save(a);
        String line = label(from) + " → " + label(to)
                + (HotelApplication.MEETING_BOOKED.equals(to) ? " · meeting " + when(a.getMeetingAt()) : "")
                + (reason == null ? "" : " · reason: " + reason);
        addLine(a, HotelApplicationNote.STATUS, line, user);
        String extra = PartnerApplicationService.note(request.note());
        if (extra != null) {
            addLine(a, HotelApplicationNote.NOTE, checkedNote(extra), user);
        }
        record(a, user, to, line);
        return view(a);
    }

    /** Add a note to the timeline (append-only). */
    @Transactional
    public ApplicationView addNote(String id, String text) {
        HospioUserDetails user = requireUpdater();
        String body = checkedNote(PartnerApplicationService.note(text));
        HotelApplication a = applications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        addLine(a, HotelApplicationNote.NOTE, body, user);
        record(a, user, "NOTE_ADDED", "note added");
        return view(a);
    }

    /** Give the application to a super admin or support admin; blank unassigns. */
    @Transactional
    public ApplicationView assign(String id, String assigneeId) {
        HospioUserDetails user = requireUpdater();
        HotelApplication a = locked(id);
        String who = StringUtils.hasText(assigneeId) ? assigneeId.trim() : null;
        String name = null;
        if (who != null) {
            name = staff().get(who);
            if (name == null) {
                throw new BadRequestException("Assign it to an active super admin or support admin");
            }
        }
        if (java.util.Objects.equals(who, a.getAssigneeId())) {
            return view(a);
        }
        a.setAssigneeId(who);
        a.setAssigneeName(name);
        applications.save(a);
        String line = who == null ? "Unassigned" : "Assigned to " + name;
        addLine(a, HotelApplicationNote.SYSTEM, line, user);
        record(a, user, "ASSIGNED", line);
        return view(a);
    }

    /** Set, move or clear the meeting time. Clearing it is refused while the status is MEETING_BOOKED. */
    @Transactional
    public ApplicationView meeting(String id, Instant meetingAt) {
        HospioUserDetails user = requireUpdater();
        HotelApplication a = locked(id);
        if (meetingAt == null && HotelApplication.MEETING_BOOKED.equals(a.getStatus())) {
            throw new BadRequestException("The meeting is booked: move it, or change the status first");
        }
        if (meetingAt != null && meetingAt.isBefore(Instant.now().minus(java.time.Duration.ofDays(366)))) {
            throw new BadRequestException("Check the meeting date");
        }
        if (java.util.Objects.equals(meetingAt, a.getMeetingAt())) {
            return view(a);
        }
        a.setMeetingAt(meetingAt);
        applications.save(a);
        String line = meetingAt == null ? "Meeting cleared" : "Meeting set for " + when(meetingAt);
        addLine(a, HotelApplicationNote.SYSTEM, line, user);
        record(a, user, "MEETING_SET", line);
        return view(a);
    }

    /**
     * Link the business the team created from this application (Start onboarding → the back-office wizard): the application becomes
     * ONBOARDED. The business must exist, and may be linked to one application only. A declined application can't be linked.
     */
    @Transactional
    public ApplicationView link(String id, String businessId) {
        HospioUserDetails user = requireUpdater();
        if (!StringUtils.hasText(businessId)) {
            throw new BadRequestException("Name the business to link");
        }
        String bid = businessId.trim();
        HotelApplication a = locked(id);
        if (bid.equals(a.getBusinessId())) {
            return view(a);
        }
        if (HotelApplication.ONBOARDED.equals(a.getStatus())) {
            throw new BadRequestException("This application is already linked to " + a.getLinkedBusinessName());
        }
        if (HotelApplication.DECLINED.equals(a.getStatus())) {
            throw new BadRequestException("This application was declined. Reopen it before linking a business");
        }
        if (applications.existsByBusinessId(bid)) {
            throw new BadRequestException("That business is already linked to another application");
        }
        TenantClient.Business business = tenants.business(bid).orElseThrow(() -> new BadRequestException("No business has that id"));
        String from = a.getStatus();
        a.setBusinessId(bid);
        a.setLinkedBusinessName(cut(business.name(), 255));
        a.setStatus(HotelApplication.ONBOARDED);
        a.setStatusChangedAt(Instant.now());
        a.setOnboardedAt(Instant.now());
        applications.save(a);
        String line = label(from) + " → " + label(HotelApplication.ONBOARDED) + " · business " + business.name();
        addLine(a, HotelApplicationNote.STATUS, line, user);
        HotelApplicationEvent.HotelApplicationEventBuilder<?, ?> e = event(a, user, "ONBOARDED", line);
        activity.record(ACTIVITY_TOPIC, e.businessId(bid).build());
        return view(a);
    }

    private HospioUserDetails requireUpdater() {
        HospioUserDetails user = CurrentUser.require();
        if (!canUpdate(user)) {
            throw new ForbiddenException("Only a super admin or support can change an application");
        }
        return user;
    }

    private HotelApplication locked(String id) {
        return applications.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }

    private static String checkedNote(String body) {
        if (body == null || body.isBlank()) {
            throw new BadRequestException("Write the note");
        }
        if (body.length() > NOTE_MAX) {
            throw new BadRequestException("Keep the note to " + NOTE_MAX + " characters");
        }
        return body;
    }

    private void addLine(HotelApplication a, String kind, String body, HospioUserDetails user) {
        notes.save(HotelApplicationNote.builder().applicationId(a.getId()).kind(kind).body(cut(body, NOTE_MAX)).authorId(user.getUserId())
                .authorName(cut(StringUtils.hasText(user.getFullName()) ? user.getFullName() : "Hospio360", 150)).build());
    }

    private ApplicationView view(HotelApplication a) {
        return ApplicationView.of(a, notes.findByApplicationIdOrderByCreatedAtAscIdAsc(a.getId()).stream().map(NoteView::of).toList());
    }

    /** Changes by Hospio360 · Admin activity: who changed what. */
    private void record(HotelApplication a, HospioUserDetails user, String change, String detail) {
        activity.record(ACTIVITY_TOPIC, event(a, user, change, detail).build());
    }

    private static HotelApplicationEvent.HotelApplicationEventBuilder<?, ?> event(HotelApplication a, HospioUserDetails user, String change, String detail) {
        return HotelApplicationEvent.builder().eventId(UUID.randomUUID().toString()).actorId(user.getUserId())
                .actorName("Hospio360 · " + user.getFullName()).occurredAt(Instant.now()).applicationId(a.getId())
                .applicantBusiness(a.getBusinessName()).change(change).detail(detail);
    }

    static String label(String status) {
        return switch (status) {
            case HotelApplication.NEW -> "New";
            case HotelApplication.CONTACTED -> "Contacted";
            case HotelApplication.MEETING_BOOKED -> "Meeting booked";
            case HotelApplication.APPROVED -> "Approved";
            case HotelApplication.ONBOARDED -> "Onboarded";
            case HotelApplication.DECLINED -> "Declined";
            default -> status;
        };
    }

    static String when(Instant at) {
        return at == null ? "—" : WHEN.format(at.atZone(LAGOS)) + " (Lagos)";
    }

    // ---- emails --------------------------------------------------------------------------------------

    String receivedEmail(HotelApplication a) {
        return "Hello " + firstName(a.getOwnerName()) + ",\n\nThank you for asking for a Hospio360 demo. We have your request and our team "
                + "will call you on " + a.getPhone() + " within 1 working day"
                + (a.getPreferredTime() == null || "ANYTIME".equals(a.getPreferredTime()) ? "" : ", in the " + a.getPreferredTime().toLowerCase(Locale.ROOT) + " if we can")
                + ".\n\nWhat you told us:\n"
                + "Business: " + a.getBusinessName() + " (" + TYPE_WORDS.get(a.getBusinessType()) + ")\n"
                + "Where: " + a.getCity() + ", " + a.getState() + "\n"
                + (a.getRooms() == null ? "" : "Rooms: " + a.getRooms() + "\n")
                + "\nOn the call we will show you Hospio360, agree what you need and set your business up with you. Your login comes "
                + "by email once your business is ready. If you did not ask for a demo, ignore this email or tell us at "
                + supportEmail() + ".\n\nHospio360\n";
    }

    String teamEmail(HotelApplication a) {
        return "A new demo request came in on hospio360.com.\n\n"
                + "Owner: " + a.getOwnerName() + "\n"
                + "Phone: " + a.getPhone() + "\n"
                + "Email: " + a.getEmail() + "\n"
                + "Business: " + a.getBusinessName() + " (" + TYPE_WORDS.get(a.getBusinessType()) + ")\n"
                + "Where: " + a.getCity() + ", " + a.getState() + "\n"
                + (a.getRooms() == null ? "" : "Rooms: " + a.getRooms() + "\n")
                + "Monthly sales (all outlets): " + MonthlySales.labelOf(a.getMonthlySales()) + "\n"
                + "Heard about us: " + HEARD.get(a.getHeardFrom()) + "\n"
                + (a.getPreferredTime() == null ? "" : "Best time to call: " + TIMES.get(a.getPreferredTime()) + "\n")
                + (a.getMarketerName() == null ? (a.getReferralCode() == null ? "" : "Referral code: " + a.getReferralCode() + " (not an active partner)\n")
                        : "Referred by: " + a.getMarketerName() + " (" + a.getReferralCode() + ")\n")
                + (a.getMessage() == null ? "" : "\nMessage:\n" + a.getMessage() + "\n")
                + "\nWe promised a call within 1 working day. Open it in the admin portal: Hotel applications.\n\nHospio360\n";
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** The referral code as sent (upper case), or null when blank or not shaped like one. */
    static String referralCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        return CODE.matcher(c).matches() ? c : null;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String firstName(String fullName) {
        return fullName.trim().split("\\s+")[0];
    }
}
