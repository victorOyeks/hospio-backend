package com.hospio.messaging.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.client.FinanceClient;
import com.hospio.messaging.client.IdentityClient;
import com.hospio.messaging.client.ReservationClient;
import com.hospio.messaging.client.TenantClient;
import com.hospio.messaging.dto.GuestInbox;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.entity.Template;
import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.repository.MessageRepository;
import com.hospio.messaging.repository.TemplateRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Guest conversations at the X-Hotel-Id hotel (docs/email.md): per booking (or guest contact),
 * messages out and in with their channel, assignment to a person, open or done. Email is sent for real through the one
 * sender; WhatsApp and SMS are recorded and shown on screen (not connected yet). Front office (Edit), managers and the
 * owner write; front office (View) reads. Templates are per hotel with placeholders; "everyone on shift" is an in-app notice
 * to today's rota.
 */
@Service
@RequiredArgsConstructor
public class GuestInboxService {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z_]+)\\s*}}|\\{([a-zA-Z_]+)}");
    public static final List<String> PLACEHOLDERS = List.of("guest", "first_name", "hotel", "room", "room_type", "check_in", "check_out", "nights", "reference");

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final TemplateRepository templates;
    private final DeliveryRecordRepository deliveryRecords;
    private final NotificationService notifications;
    private final DeliveryService deliveries;
    private final IdentityClient identity;
    private final ReservationClient reservations;
    private final FinanceClient finance;
    private final TenantClient tenants;

    public record Filter(String tab, String q, NotificationChannel channel) {
    }

    // ---- who ---------------------------------------------------------------------------------------------

    static boolean canRead(HospioUserDetails u) {
        return u.isManagerOrOwner() || u.has(Role.FRONT_OFFICE) || u.isPlatform();
    }

    static boolean canWrite(HospioUserDetails u) {
        return !u.isReadOnly() && (u.isManagerOrOwner() || u.has(Role.FRONT_OFFICE, AccessLevel.EDIT));
    }

    private static HospioUserDetails reader() {
        HospioUserDetails u = CurrentUser.require();
        if (!canRead(u)) {
            throw new ForbiddenException("Guest messages are for front office, managers and the owner");
        }
        return u;
    }

    private static HospioUserDetails writer() {
        HospioUserDetails u = CurrentUser.require();
        if (!canWrite(u)) {
            throw new ForbiddenException(canRead(u) ? "You can read guest messages but not send them" : "Guest messages are for front office, managers and the owner");
        }
        return u;
    }

    private static HospioUserDetails manager() {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly() || !u.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can do that");
        }
        return u;
    }

    // ---- conversations ----------------------------------------------------------------------------------

    /** Tabs waiting (the guest wrote last), open, mine (assigned to me, open), done, all; counts per tab over the search. */
    @Transactional(readOnly = true)
    public PageWithCounts<GuestInbox.ConversationView> page(Filter f, Integer page, Integer size) {
        HospioUserDetails me = reader();
        String hotel = TenantContext.requireHotel();
        Specification<Conversation> base = guestAt(hotel).and(search(f.q(), f.channel()));
        String tab = f.tab() == null || f.tab().isBlank() ? "open" : f.tab().trim().toLowerCase(Locale.ROOT);
        Specification<Conversation> spec = switch (tab) {
            case "waiting" -> base.and(waiting());
            case "open" -> base.and(status(Conversation.Status.OPEN));
            case "mine" -> base.and(status(Conversation.Status.OPEN)).and(assignedTo(me.getUserId()));
            case "done" -> base.and(status(Conversation.Status.CLOSED));
            case "all" -> base;
            default -> throw new BadRequestException("tab is waiting, open, mine, done or all");
        };
        Sort sort = Sort.by(Sort.Order.desc("lastMessageAt").nullsLast(), Sort.Order.desc("id"));
        Page<Conversation> result = conversations.findAll(spec, Paging.of(page, size, sort));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("waiting", conversations.count(base.and(waiting())));
        counts.put("open", conversations.count(base.and(status(Conversation.Status.OPEN))));
        counts.put("mine", conversations.count(base.and(status(Conversation.Status.OPEN)).and(assignedTo(me.getUserId()))));
        counts.put("done", conversations.count(base.and(status(Conversation.Status.CLOSED))));
        counts.put("all", conversations.count(base));
        return PageWithCounts.of(result, GuestInboxService::view, counts, Map.of());
    }

    @Transactional(readOnly = true)
    public GuestInbox.Detail detail(String id) {
        reader();
        Conversation c = find(id);
        return new GuestInbox.Detail(view(c), messages.findByConversationIdOrderByCreatedAt(id).stream().map(GuestInboxService::view).toList());
    }

    /** A message to a guest: the booking's conversation (reopened if done) or a new one. */
    @Transactional
    public GuestInbox.Sent message(GuestInbox.NewMessage r) {
        HospioUserDetails me = writer();
        String hotel = TenantContext.requireHotel();
        NotificationChannel channel = r.channel() == null ? NotificationChannel.EMAIL : r.channel();
        if (channel == NotificationChannel.IN_APP) {
            throw new BadRequestException("Pick email, WhatsApp or SMS for a guest");
        }
        Conversation c;
        if (StringUtils.hasText(r.bookingId())) {
            ReservationClient.Booking b = reservations.booking(r.bookingId().trim())
                    .filter(x -> x.hotelId() == null || hotel.equals(x.hotelId()))
                    .orElseThrow(() -> new ResourceNotFoundException("That booking is not at this hotel"));
            c = conversations.findFirstByHotelIdAndKindAndBookingIdOrderByLastMessageAtDesc(hotel, Conversation.Kind.GUEST, b.id())
                    .orElseGet(() -> Conversation.builder().kind(Conversation.Kind.GUEST).bookingId(b.id()).build());
            c.setGuestId(b.guestId());
            c.setGuestName(b.guestName());
            c.setBookingRef(b.reference());
            c.setRoomNumber(b.roomNumber());
            c.setRoomType(b.roomType());
            c.setCheckIn(b.checkIn());
            c.setCheckOut(b.checkOut());
            if (StringUtils.hasText(b.guestEmail())) {
                c.setGuestEmail(b.guestEmail().trim());
            }
            if (StringUtils.hasText(b.guestPhone())) {
                c.setGuestPhone(b.guestPhone().trim());
            }
        } else {
            String name = r.guestName() == null ? "" : r.guestName().trim();
            if (name.isEmpty()) {
                throw new BadRequestException("Say who the guest is, or pick their booking");
            }
            String email = StringUtils.hasText(r.guestEmail()) ? r.guestEmail().trim()
                    : channel == NotificationChannel.EMAIL && StringUtils.hasText(r.guestContact()) ? r.guestContact().trim() : null;
            String phone = StringUtils.hasText(r.guestPhone()) ? r.guestPhone().trim()
                    : channel != NotificationChannel.EMAIL && StringUtils.hasText(r.guestContact()) ? r.guestContact().trim() : null;
            String contact = channel == NotificationChannel.EMAIL ? email : phone;
            Optional<Conversation> open = contact == null ? Optional.empty()
                    : conversations.findFirstByHotelIdAndGuestContactAndStatus(hotel, contact, Conversation.Status.OPEN);
            c = open.orElseGet(() -> Conversation.builder().kind(Conversation.Kind.GUEST).guestId(r.guestId()).guestName(name).build());
            if (email != null) {
                c.setGuestEmail(email);
            }
            if (phone != null) {
                c.setGuestPhone(phone);
            }
        }
        if (c.getAssignedTo() == null) {
            c.setAssignedTo(me.getUserId());
            c.setAssignedToName(me.getFullName());
        }
        if (StringUtils.hasText(r.subject())) {
            c.setSubject(r.subject().trim());
        }
        return send(c, me, channel, r.body(), c.getSubject());
    }

    @Transactional
    public GuestInbox.Sent reply(String id, GuestInbox.Reply r) {
        HospioUserDetails me = writer();
        Conversation c = find(id);
        if (c.getAssignedTo() == null) {
            c.setAssignedTo(me.getUserId());
            c.setAssignedToName(me.getFullName());
        }
        if (StringUtils.hasText(r.subject())) {
            c.setSubject(r.subject().trim());
        }
        return send(c, me, r.channel() == null ? c.getChannel() : r.channel(), r.body(), c.getSubject());
    }

    /** What the guest said by phone, WhatsApp or at the desk, logged so the conversation is whole. Reopens a done one. */
    @Transactional
    public GuestInbox.Detail guestReply(String id, GuestInbox.GuestReply r) {
        HospioUserDetails me = writer();
        Conversation c = find(id);
        messages.save(Message.builder().conversationId(c.getId()).direction(Message.Direction.IN).senderName(c.getGuestName())
                .body(r.body().trim()).channel(c.getChannel() == null ? null : c.getChannel().name()).status("LOGGED")
                .providerRef("logged by " + cut(me.getFullName(), 80)).build());
        c.setLastMessageAt(Instant.now());
        c.setLastDirection("IN");
        c.setLastPreview(cut(r.body().trim(), 300));
        c.setStatus(Conversation.Status.OPEN);
        c.setDoneAt(null);
        c.setDoneByName(null);
        conversations.save(c);
        return detail(c.getId());
    }

    @Transactional
    public GuestInbox.ConversationView assign(String id, GuestInbox.Assign r) {
        HospioUserDetails me = manager();
        Conversation c = find(id);
        IdentityClient.Contact who = identity.contact(r.userId())
                .orElseThrow(() -> new BadRequestException("That person is not in your business"));
        if (who.businessId() == null || !who.businessId().equals(me.getBusinessId())) {
            throw new BadRequestException("That person is not in your business");
        }
        if (!"ACTIVE".equalsIgnoreCase(who.status())) {
            throw new BadRequestException(who.fullName() + " can't take conversations right now (" + String.valueOf(who.status()).toLowerCase(Locale.ROOT) + ")");
        }
        if (who.roles().stream().noneMatch(x -> x.equals("FRONT_OFFICE") || x.equals("MANAGER") || x.equals("OWNER"))) {
            throw new BadRequestException(who.fullName() + " doesn't work with guest messages (front office or a manager only)");
        }
        if (who.id().equals(c.getAssignedTo())) {
            throw new BadRequestException("It's already with " + who.fullName());
        }
        c.setAssignedTo(who.id());
        c.setAssignedToName(who.fullName());
        conversations.save(c);
        if (!who.id().equals(me.getUserId())) {
            notifications.notify(null, who.id(), null, "GUEST_MESSAGE", me.getFullName() + " gave you a guest conversation",
                    nz(c.getGuestName()) + (c.getLastPreview() == null ? "" : ": " + c.getLastPreview()), "/messages?open=" + c.getId());
        }
        return view(c);
    }

    @Transactional
    public GuestInbox.ConversationView done(String id) {
        HospioUserDetails me = writer();
        Conversation c = find(id);
        if (c.getStatus() == Conversation.Status.CLOSED) {
            throw new BadRequestException("It's already marked done");
        }
        c.setStatus(Conversation.Status.CLOSED);
        c.setDoneAt(Instant.now());
        c.setDoneByName(me.getFullName());
        return view(conversations.save(c));
    }

    @Transactional
    public GuestInbox.ConversationView reopen(String id) {
        writer();
        Conversation c = find(id);
        if (c.getStatus() == Conversation.Status.OPEN) {
            throw new BadRequestException("It's already open");
        }
        c.setStatus(Conversation.Status.OPEN);
        c.setDoneAt(null);
        c.setDoneByName(null);
        return view(conversations.save(c));
    }

    private GuestInbox.Sent send(Conversation c, HospioUserDetails me, NotificationChannel channel, String text, String subject) {
        if (channel == null || channel == NotificationChannel.IN_APP) {
            throw new BadRequestException("Pick email, WhatsApp or SMS for a guest");
        }
        String body = text.trim();
        String to = channel == NotificationChannel.EMAIL ? c.getGuestEmail() : c.getGuestPhone();
        if (!StringUtils.hasText(to)) {
            throw new BadRequestException(channel == NotificationChannel.EMAIL
                    ? nz(c.getGuestName()) + " has no email; add it to the guest or pick WhatsApp (coming soon)"
                    : nz(c.getGuestName()) + " has no phone number for " + (channel == NotificationChannel.SMS ? "SMS" : "WhatsApp"));
        }
        if (channel == NotificationChannel.EMAIL && !to.contains("@")) {
            throw new BadRequestException(to + " is not an email address");
        }
        c.setChannel(channel);
        c.setGuestContact(to);
        c.setLastMessageAt(Instant.now());
        c.setLastDirection("OUT");
        c.setLastPreview(cut(body, 300));
        c.setStatus(Conversation.Status.OPEN);
        c.setDoneAt(null);
        c.setDoneByName(null);
        c = conversations.save(c);
        Delivery d = new Delivery(channel == NotificationChannel.EMAIL ? Delivery.Channel.EMAIL : channel == NotificationChannel.SMS ? Delivery.Channel.SMS
                : Delivery.Channel.WHATSAPP, to, StringUtils.hasText(subject) ? subject : "A message from your hotel", body, Delivery.Kind.MESSAGE, null);
        String status = deliveries.marked(d).status();
        Message m = messages.save(Message.builder().conversationId(c.getId()).direction(Message.Direction.OUT).senderId(me.getUserId())
                .senderName(me.getFullName()).body(body).channel(channel.name())
                .status(Delivery.EMAIL_SENT.equals(status) ? DeliveryRecord.Status.QUEUED.name() : DeliveryRecord.Status.NOT_CONNECTED.name()).build());
        List<Delivery> echoed = deliveries.send(new Delivery(d.channel(), d.to(), d.subject(), d.text(), d.kind(), m.getId()));
        return new GuestInbox.Sent(view(c), view(m), echoed);
    }

    private Conversation find(String id) {
        String hotel = TenantContext.requireHotel();
        return conversations.findById(id).filter(c -> c.getKind() == Conversation.Kind.GUEST && hotel.equals(c.getHotelId()))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    // ---- templates --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<GuestInbox.TemplateView> templates() {
        reader();
        return templates.findByHotelIdAndPurposeOrderByNameAsc(TenantContext.requireHotel(), "MESSAGE").stream().map(GuestInboxService::view).toList();
    }

    @Transactional
    public GuestInbox.TemplateView saveTemplate(String id, GuestInbox.TemplateRequest r) {
        manager();
        String hotel = TenantContext.requireHotel();
        String name = r.name().trim();
        Template t = id == null ? Template.builder().purpose("MESSAGE").active(true).build() : templates.findById(id)
                .filter(x -> hotel.equals(x.getHotelId()) && "MESSAGE".equals(x.getPurpose()))
                .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        if (templates.existsByHotelIdAndPurposeAndNameIgnoreCaseAndIdNot(hotel, "MESSAGE", name, id == null ? "" : id)) {
            throw new ConflictException("There is already a template called " + name);
        }
        List<String> unknown = unknownPlaceholders(r.body() + " " + nz(r.subject()));
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown placeholder " + String.join(", ", unknown) + "; use " + String.join(", ", PLACEHOLDERS.stream().map(p -> "{{" + p + "}}").toList()));
        }
        t.setName(name);
        t.setSubject(StringUtils.hasText(r.subject()) ? r.subject().trim() : null);
        t.setBody(r.body().trim());
        t.setChannel(r.channel() == null ? NotificationChannel.EMAIL : r.channel());
        if (t.getKey() == null) {
            t.setKey(cut("MSG_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT), 60));
        }
        return view(templates.save(t));
    }

    @Transactional
    public void deleteTemplate(String id) {
        manager();
        String hotel = TenantContext.requireHotel();
        Template t = templates.findById(id).filter(x -> hotel.equals(x.getHotelId()) && "MESSAGE".equals(x.getPurpose()))
                .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        templates.delete(t);
    }

    /** The template filled from a conversation's booking (or a booking): what's left unfilled is listed in {@code missing}. */
    @Transactional(readOnly = true)
    public GuestInbox.Filled fill(String id, GuestInbox.Fill r) {
        reader();
        String hotel = TenantContext.requireHotel();
        Template t = templates.findById(id).filter(x -> hotel.equals(x.getHotelId()))
                .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("hotel", tenants.hotel(hotel).map(TenantClient.Sender::name).orElse(null));
        if (r != null && StringUtils.hasText(r.conversationId())) {
            Conversation c = find(r.conversationId());
            stay(values, c.getGuestName(), c.getRoomNumber(), c.getRoomType(), c.getCheckIn(), c.getCheckOut(), c.getBookingRef());
        } else if (r != null && StringUtils.hasText(r.bookingId())) {
            ReservationClient.Booking b = reservations.booking(r.bookingId()).orElseThrow(() -> new ResourceNotFoundException("That booking is not at this hotel"));
            stay(values, b.guestName(), b.roomNumber(), b.roomType(), b.checkIn(), b.checkOut(), b.reference());
        }
        List<String> missing = new ArrayList<>();
        String subject = t.getSubject() == null ? null : fillText(t.getSubject(), values, missing);
        String body = fillText(t.getBody(), values, missing);
        return new GuestInbox.Filled(subject, body, missing.stream().distinct().toList());
    }

    static void stay(Map<String, String> v, String guest, String room, String roomType, LocalDate in, LocalDate out, String ref) {
        v.put("guest", guest);
        v.put("first_name", guest == null ? null : guest.trim().split("\\s+")[0]);
        v.put("room", room);
        v.put("room_type", roomType);
        v.put("check_in", in == null ? null : DAY.format(in));
        v.put("check_out", out == null ? null : DAY.format(out));
        v.put("nights", in == null || out == null ? null : String.valueOf(ChronoUnit.DAYS.between(in, out)));
        v.put("reference", ref);
    }

    /** {{name}} (and the older {name}) replaced by its value; one without a value stays as it is and goes on {@code missing}. */
    static String fillText(String text, Map<String, String> values, List<String> missing) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
            String v = values.get(key);
            if (v == null || v.isBlank()) {
                if (PLACEHOLDERS.contains(key)) {
                    missing.add(key);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static List<String> unknownPlaceholders(String text) {
        Matcher m = Pattern.compile("\\{\\{\\s*([a-zA-Z_]+)\\s*}}").matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.ROOT);
            if (!PLACEHOLDERS.contains(k)) {
                out.add("{{" + m.group(1) + "}}");
            }
        }
        return out.stream().distinct().toList();
    }

    // ---- everyone on shift ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FinanceClient.OnShift> onShift() {
        manager();
        TenantContext.requireHotel();
        return finance.onShiftToday();
    }

    /** An in-app notice to everyone on today's rota at this hotel (not the sender). */
    @Transactional
    public GuestInbox.ShiftNoticeSent shiftNotice(GuestInbox.ShiftNotice r) {
        HospioUserDetails me = manager();
        TenantContext.requireHotel();
        List<FinanceClient.OnShift> people = finance.onShiftToday().stream().filter(p -> !p.userId().equals(me.getUserId())).toList();
        if (people.isEmpty()) {
            throw new BadRequestException("Nobody else is on today's rota");
        }
        String body = r.body().trim();
        for (FinanceClient.OnShift p : people) {
            notifications.notify(null, p.userId(), null, "SHIFT_NOTICE", "From " + me.getFullName() + " to everyone on shift", body, "/notifications");
        }
        return new GuestInbox.ShiftNoticeSent(people.size(), people.stream().map(FinanceClient.OnShift::name).toList());
    }

    // ---- the delivery log ----------------------------------------------------------------------------------

    /** Every email, SMS and WhatsApp the hotel sent (business-wide for the owner without X-Hotel-Id), newest first. */
    @Transactional(readOnly = true)
    public PageWithCounts<DeliveryRecord> deliveryLog(String status, String channel, String q, Integer page, Integer size) {
        HospioUserDetails u = CurrentUser.require();
        if (!u.isPlatform() && (u.isReadOnly() || !u.isManagerOrOwner())) {
            throw new ForbiddenException("Only a manager or the owner can see what was sent");
        }
        String hotel = TenantContext.current().map(t -> t.hotelId()).orElse(null);
        Specification<DeliveryRecord> base = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (hotel != null) {
                ps.add(cb.equal(root.get("hotelId"), hotel));
            }
            if (StringUtils.hasText(channel)) {
                ps.add(cb.equal(root.get("channel"), channel.trim().toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(cb.like(cb.lower(root.get("toAddress")), like, '\\'), cb.like(cb.lower(cb.coalesce(root.<String>get("subject"), "")), like, '\\')));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Specification<DeliveryRecord> spec = base;
        if (StringUtils.hasText(status)) {
            DeliveryRecord.Status st;
            try {
                st = DeliveryRecord.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("status is QUEUED, SENT, FAILED, NOT_CONNECTED or DISABLED");
            }
            spec = base.and((root, cq, cb) -> cb.equal(root.get("status"), st));
        }
        Page<DeliveryRecord> result = deliveryRecords.findAll(spec, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DeliveryRecord.Status st : DeliveryRecord.Status.values()) {
            counts.put(st.name().toLowerCase(Locale.ROOT), deliveryRecords.count(base.and((root, cq, cb) -> cb.equal(root.get("status"), st))));
        }
        counts.put("all", deliveryRecords.count(base));
        return PageWithCounts.of(result, x -> x, counts, Map.of());
    }

    // ---- views and specs --------------------------------------------------------------------------------

    static GuestInbox.ConversationView view(Conversation c) {
        return new GuestInbox.ConversationView(c.getId(), c.getGuestId(), c.getGuestName(), c.getGuestEmail(), c.getGuestPhone(), c.getGuestContact(),
                c.getChannel(), c.getSubject(), c.getBookingId(), c.getBookingRef(), c.getRoomNumber(), c.getRoomType(), c.getCheckIn(), c.getCheckOut(),
                c.getAssignedTo(), c.getAssignedToName(), c.getStatus() == Conversation.Status.CLOSED ? "DONE" : "OPEN",
                c.getStatus() == Conversation.Status.OPEN && "IN".equals(c.getLastDirection()), c.getLastMessageAt(), c.getLastPreview(),
                c.getDoneAt(), c.getDoneByName());
    }

    static GuestInbox.MessageView view(Message m) {
        return new GuestInbox.MessageView(m.getId(), m.getDirection().name(), m.getBody(), m.getSenderName(), m.getCreatedAt(), m.getChannel(),
                m.getStatus(), m.getError());
    }

    static GuestInbox.TemplateView view(Template t) {
        return new GuestInbox.TemplateView(t.getId(), t.getName() == null ? t.getKey() : t.getName(), t.getSubject(), t.getBody(), t.getChannel(),
                t.getPurpose(), t.getKey(), t.isActive(), t.getUpdatedAt());
    }

    private static Specification<Conversation> guestAt(String hotel) {
        return (root, q, cb) -> cb.and(cb.equal(root.get("hotelId"), hotel), cb.equal(root.get("kind"), Conversation.Kind.GUEST));
    }

    private static Specification<Conversation> status(Conversation.Status s) {
        return (root, q, cb) -> cb.equal(root.get("status"), s);
    }

    private static Specification<Conversation> waiting() {
        return (root, q, cb) -> cb.and(cb.equal(root.get("status"), Conversation.Status.OPEN), cb.equal(root.get("lastDirection"), "IN"));
    }

    private static Specification<Conversation> assignedTo(String userId) {
        return (root, q, cb) -> cb.equal(root.get("assignedTo"), userId);
    }

    private static Specification<Conversation> search(String text, NotificationChannel channel) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(text)) {
                String like = Paging.like(text);
                ps.add(cb.or(Map.of("guestName", 1, "guestContact", 2, "guestEmail", 3, "guestPhone", 4, "bookingRef", 5, "roomNumber", 6,
                                "lastPreview", 7, "subject", 8, "assignedToName", 9).keySet().stream()
                        .map(f -> cb.like(cb.lower(cb.coalesce(root.<String>get(f), "")), like, '\\')).toArray(Predicate[]::new)));
            }
            if (channel != null) {
                ps.add(cb.equal(root.get("channel"), channel));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
