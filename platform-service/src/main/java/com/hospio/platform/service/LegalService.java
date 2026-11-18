package com.hospio.platform.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.LegalAcceptance;
import com.hospio.platform.entity.LegalDocument;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.repository.LegalAcceptanceRepository;
import com.hospio.platform.repository.LegalDocumentRepository;
import com.hospio.platform.repository.MarketerProfileRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Online acceptance of the legal documents (docs/legal-acceptance.md). One system for everyone: owners accept the Terms, Privacy Policy,
 * Data Processing Terms and the daily debit authority (at self sign-up, at the handover, or at a later sign-in), staff accept the Staff
 * terms, marketers the Referral partner terms, owners the Devices terms per device package. A new version of a document makes it pending
 * again from its effective date. Every acceptance is an append-only {@link LegalAcceptance} with who, which version, when, IP, browser
 * and how.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalService {

    public static final String TERMS = "TERMS";
    public static final String PRIVACY = "PRIVACY";
    public static final String DPA = "DPA";
    public static final String DEBIT = "DEBIT";
    public static final String DEVICES = "DEVICES";
    public static final String SUPPORT = "SUPPORT";
    public static final String RPA = "RPA";
    public static final String STAFF = "STAFF";

    public static final List<String> OWNER_DOCS = List.of(TERMS, PRIVACY, DPA, DEBIT);
    public static final List<String> KEYS = List.of(TERMS, PRIVACY, DPA, DEBIT, DEVICES, SUPPORT, RPA, STAFF);

    /** How an acceptance was given. The client may name the first three; the rest the server decides. */
    public static final Set<String> CLIENT_METHODS = Set.of("SELF_SIGN_UP", "HANDOVER", "PORTAL");
    public static final Set<String> CHANNELS = Set.of("WHATSAPP", "EMAIL", "SMS", "IN_PERSON", "PHONE_CALL", "OTHER");

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final Set<Role> STAFF_ROLES = Set.of(Role.MANAGER, Role.FRONT_OFFICE, Role.HOUSEKEEPING, Role.LAUNDRY, Role.MAINTENANCE,
            Role.KITCHEN, Role.BAR, Role.INVENTORY, Role.FINANCE, Role.WAITER, Role.CASHIER);

    private final LegalDocumentRepository documents;
    private final LegalAcceptanceRepository acceptances;
    private final MarketerProfileRepository marketerProfiles;
    private final BusinessNames businessNames;
    private final FeeService fees;

    @Value("${hospio.legal.site-url:https://hospio360.com}")
    private String siteUrl;

    @Value("${hospio.legal.material-notice-days:30}")
    private int materialNoticeDays;

    // ---- views -----------------------------------------------------------------------------------------

    /** {@code ratePct}: on DEBIT only, the share of daily sales the box states (the business's rate for its owner, else the default). */
    public record DocView(String key, String version, String title, String slug, String url, Instant effectiveFrom, boolean material,
                          String summary, BigDecimal ratePct) {
    }

    public record RecordView(String id, String event, String method, String docKey, String title, String version, String userId, String userName,
                             String userEmail, String role, String businessId, String businessName, String hotelId, String reference, String channel,
                             LocalDate sharedOn, String note, String ip, String userAgent, String recordedByName, Instant at, BigDecimal ratePct) {
    }

    /** What the signed-in person must accept now ({@code pending}), what is coming with notice, and what they accepted. */
    public record MyLegal(List<DocView> required, List<DocView> pending, List<DocView> upcoming, List<RecordView> records, boolean canAccept) {
    }

    /** {@code ratePct}: for DEBIT, the rate the box showed; missing means the platform default (older clients always said 1.5%). */
    public record AcceptItem(String key, String version, BigDecimal ratePct) {

        public AcceptItem(String key, String version) {
            this(key, version, null);
        }
    }

    public record AcceptRequest(List<AcceptItem> documents, String method) {
    }

    public record LinksSharedRequest(String channel, LocalDate sharedOn, String note) {
    }

    public record PublishRequest(String key, String version, String title, LocalDate effectiveFrom, Boolean material, String summary) {
    }

    // ---- documents ------------------------------------------------------------------------------------

    /** Every version, current first per document. */
    @Transactional(readOnly = true)
    public List<LegalDocument> allVersions() {
        return asSystem(documents::findAllByOrderByDocKeyAscEffectiveFromDescCreatedAtDesc);
    }

    /** The version in force now for each document (by key). */
    @Transactional(readOnly = true)
    public Map<String, LegalDocument> current() {
        return current(Instant.now());
    }

    Map<String, LegalDocument> current(Instant now) {
        Map<String, LegalDocument> out = new LinkedHashMap<>();
        for (LegalDocument d : allVersions()) {
            if (!d.getEffectiveFrom().isAfter(now)) {
                out.putIfAbsent(d.getDocKey(), d);
            }
        }
        return out;
    }

    /** Versions announced but not yet in force (newest per document). */
    @Transactional(readOnly = true)
    public List<LegalDocument> upcoming() {
        Instant now = Instant.now();
        Map<String, LegalDocument> out = new LinkedHashMap<>();
        for (LegalDocument d : allVersions()) {
            if (d.getEffectiveFrom().isAfter(now)) {
                out.putIfAbsent(d.getDocKey(), d);
            }
        }
        return new ArrayList<>(out.values());
    }

    @Transactional(readOnly = true)
    public String currentVersion(String key) {
        LegalDocument d = current().get(key);
        return d == null ? null : d.getVersion();
    }

    /** The public list for the sign-up page and the landing site: the version in force of each document, and what is coming. */
    @Transactional(readOnly = true)
    public Map<String, List<DocView>> publicDocuments() {
        BigDecimal rate = fees.defaultSalesPct();
        return Map.of("current", current().values().stream().map(d -> view(d, rate)).toList(), "upcoming",
                upcoming().stream().map(d -> view(d, rate)).toList());
    }

    /** A new version (SUPER_ADMIN). A material change needs {@code material-notice-days} of notice; others may start at once. */
    @Transactional
    public LegalDocument publish(PublishRequest r) {
        HospioUserDetails user = CurrentUser.require();
        String key = key(r.key());
        String version = r.version() == null ? "" : r.version().trim();
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,39}")) {
            throw new BadRequestException("Version: 2 to 40 letters, digits, dots or dashes, like TOS-2027-01");
        }
        boolean material = Boolean.TRUE.equals(r.material());
        LocalDate today = LocalDate.now(LAGOS);
        LocalDate from = r.effectiveFrom() == null ? today : r.effectiveFrom();
        if (from.isBefore(today)) {
            throw new BadRequestException("The effective date can't be in the past");
        }
        if (material && from.isBefore(today.plusDays(materialNoticeDays))) {
            throw new BadRequestException("A material change needs " + materialNoticeDays + " days' notice: effective "
                    + today.plusDays(materialNoticeDays) + " or later");
        }
        return asSystem(() -> {
            if (documents.findByDocKeyAndVersion(key, version).isPresent()) {
                throw new ConflictException(key + " " + version + " already exists");
            }
            LegalDocument base = current().get(key);
            String title = StringUtils.hasText(r.title()) ? r.title().trim() : base != null ? base.getTitle() : key;
            String slug = base != null ? base.getSlug() : key.toLowerCase(Locale.ROOT);
            Instant effective = from.equals(today) ? Instant.now() : from.atStartOfDay(LAGOS).toInstant();
            LegalDocument d = documents.save(LegalDocument.builder().docKey(key).version(version).title(title).slug(slug).effectiveFrom(effective)
                    .material(material).summary(trim(r.summary(), 500)).createdByName(user.getFullName()).build());
            log.info("Legal document {} {} published by {} (effective {}, material {})", key, version, user.getUserId(), effective, material);
            return d;
        });
    }

    // ---- the signed-in person ------------------------------------------------------------------------

    /** The documents this person must have accepted: owners four, staff one, marketers one; Hospio360 staff none. */
    public static List<String> requiredKeys(HospioUserDetails user) {
        if (user == null || user.isPlatform()) {
            return List.of();
        }
        if (user.has(Role.OWNER)) {
            return OWNER_DOCS;
        }
        if (user.isMarketer()) {
            return List.of(RPA);
        }
        if (user.roleSet().stream().anyMatch(STAFF_ROLES::contains)) {
            return List.of(STAFF);
        }
        return List.of();
    }

    @Transactional(readOnly = true)
    public MyLegal me() {
        HospioUserDetails user = CurrentUser.require();
        Map<String, LegalDocument> current = current();
        List<String> keys = requiredKeys(user);
        return asSystem(() -> {
            List<LegalAcceptance> mine = acceptances.findByUserIdAndEventOrderByCreatedAtDesc(user.getUserId(), LegalAcceptance.ACCEPTED);
            List<DocView> required = new ArrayList<>();
            List<DocView> pending = new ArrayList<>();
            // The debit box states the business's own rate; worked out only when the box is to be shown.
            LegalDocument debit = current.get(DEBIT);
            boolean debitPending = keys.contains(DEBIT) && debit != null
                    && mine.stream().noneMatch(a -> DEBIT.equals(a.getDocKey()) && debit.getVersion().equals(a.getVersion()));
            BigDecimal rate = debitPending ? fees.dailySalesPct(user.getBusinessId()) : null;
            for (String k : keys) {
                LegalDocument d = current.get(k);
                if (d == null) {
                    continue;
                }
                required.add(view(d, rate));
                boolean done = mine.stream().anyMatch(a -> k.equals(a.getDocKey()) && d.getVersion().equals(a.getVersion()));
                if (!done) {
                    pending.add(view(d, rate));
                }
            }
            List<DocView> coming = upcoming().stream().filter(d -> keys.contains(d.getDocKey())).map(d -> view(d, null)).toList();
            return new MyLegal(required, pending, coming, mine.stream().limit(100).map(this::record).toList(), !(user.getActedBy() != null));
        });
    }

    /**
     * The signed-in person accepts the versions in force of their documents. Each must be one of theirs and the current version (a
     * newer one appeared → 409, reload). Accepting what is already accepted records nothing.
     */
    @Transactional
    public MyLegal accept(AcceptRequest r) {
        HospioUserDetails user = CurrentUser.require();
        if ((user.getActedBy() != null)) {
            throw new ForbiddenException("Only the person themselves can accept the terms");
        }
        if (r == null || r.documents() == null || r.documents().isEmpty()) {
            throw new BadRequestException("Tick the boxes to accept");
        }
        List<String> keys = requiredKeys(user);
        Map<String, LegalDocument> current = current();
        String asked = r.method() == null ? null : r.method().trim().toUpperCase(Locale.ROOT);
        if (asked != null && !asked.isEmpty() && !CLIENT_METHODS.contains(asked)) {
            throw new BadRequestException("Unknown way of accepting: " + r.method());
        }
        Client client = Client.current();
        String businessName = StringUtils.hasText(user.getBusinessId()) ? businessNames.name(user.getBusinessId()) : null;
        asSystem(() -> {
            for (AcceptItem item : r.documents()) {
                String key = key(item.key());
                if (!keys.contains(key)) {
                    throw new BadRequestException("You don't need to accept " + key);
                }
                LegalDocument d = current.get(key);
                if (d == null || !d.getVersion().equals(item.version())) {
                    throw new ConflictException("There is a newer version of " + (d == null ? key : d.getTitle()) + ". Reload the page and read it");
                }
                if (acceptances.existsByUserIdAndDocKeyAndVersionAndEvent(user.getUserId(), key, d.getVersion(), LegalAcceptance.ACCEPTED)) {
                    continue;
                }
                BigDecimal rate = DEBIT.equals(key) ? debitRateShown(user, item) : null;
                String method = StringUtils.hasText(asked) ? asked
                        : acceptances.existsByUserIdAndDocKeyAndEvent(user.getUserId(), key, LegalAcceptance.ACCEPTED) ? "RE_ACCEPTANCE" : "FIRST_SIGN_IN";
                acceptances.save(base(user, client, businessName).event(LegalAcceptance.ACCEPTED).method(method).docKey(key).version(d.getVersion())
                        .ratePct(rate).build());
                if (RPA.equals(key)) {
                    marketerAccepted(user, d.getVersion());
                }
                log.info("User {} accepted {} {} ({})", user.getUserId(), key, d.getVersion(), method);
            }
            return null;
        });
        return me();
    }

    /** The owner accepts the Devices terms for one package (DevicePackageService); returns the version accepted. */
    @Transactional
    public String acceptDevices(String packageId, String hotelId, String hotelName, String version) {
        HospioUserDetails user = CurrentUser.require();
        if ((user.getActedBy() != null) || !user.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner can accept the device terms");
        }
        LegalDocument d = current().get(DEVICES);
        if (d == null) {
            throw new BadRequestException("The device terms are not published yet");
        }
        if (version != null && !d.getVersion().equals(version)) {
            throw new ConflictException("There is a newer version of the device terms. Reload the page and read it");
        }
        Client client = Client.current();
        String businessName = businessNames.name(user.getBusinessId());
        asSystem(() -> acceptances.save(base(user, client, businessName).event(LegalAcceptance.ACCEPTED).method("DEVICE_PACKAGE").docKey(DEVICES)
                .version(d.getVersion()).hotelId(hotelId).hotelName(hotelName).reference(packageId).build()));
        return d.getVersion();
    }

    /** The back office notes how it shared the links with an owner it onboarded (handover step). Not an acceptance. */
    @Transactional
    public List<RecordView> linksShared(String businessId, LinksSharedRequest r) {
        HospioUserDetails user = CurrentUser.require();
        String channel = r == null || r.channel() == null ? "" : r.channel().trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            throw new BadRequestException("Say how the links were shared: WhatsApp, email, SMS, in person, phone call or other");
        }
        LocalDate on = r.sharedOn() == null ? LocalDate.now(LAGOS) : r.sharedOn();
        if (on.isAfter(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The date the links were shared can't be in the future");
        }
        String note = trim(r.note(), 300);
        if ("OTHER".equals(channel) && !StringUtils.hasText(note)) {
            throw new BadRequestException("Say how the links were shared (note)");
        }
        String businessName = businessNames.name(businessId);
        Map<String, LegalDocument> current = current();
        Client client = Client.current();
        return asSystem(() -> {
            List<RecordView> out = new ArrayList<>();
            for (String k : OWNER_DOCS) {
                LegalDocument d = current.get(k);
                if (d == null) {
                    continue;
                }
                out.add(record(acceptances.save(LegalAcceptance.builder().event(LegalAcceptance.LINKS_SHARED).method("BACK_OFFICE").docKey(k)
                        .version(d.getVersion()).businessId(businessId).businessName(businessName).channel(channel).sharedOn(on).note(note)
                        .recordedBy(user.getUserId()).recordedByName(user.getFullName()).ip(client.ip()).userAgent(client.agent()).build())));
            }
            return out;
        });
    }

    // ---- records --------------------------------------------------------------------------------------

    /** A business's records, newest first: admins any business, the owner their own. */
    @Transactional(readOnly = true)
    public List<RecordView> forBusiness(String businessId) {
        HospioUserDetails user = CurrentUser.require();
        String business = user.isPlatform() ? businessId : user.getBusinessId();
        if (!StringUtils.hasText(business)) {
            throw new BadRequestException("Say which business (businessId)");
        }
        if (!user.isPlatform() && !user.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner can see the business's acceptances");
        }
        return asSystem(() -> acceptances.findByBusinessIdOrderByCreatedAtDesc(business).stream().map(this::record).toList());
    }

    /** The same records as CSV (admin export). */
    @Transactional(readOnly = true)
    public String csv(String businessId) {
        StringBuilder sb = new StringBuilder("When (Lagos),Event,How,Document,Version,Person,Email,Role,Business,Hotel,Reference,Channel,Shared on,Note,IP,Browser,Recorded by,Record id\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(LAGOS);
        for (RecordView r : forBusiness(businessId)) {
            sb.append(String.join(",", List.of(f.format(r.at()), r.event(), r.method(), cell(r.title()), r.version(), cell(r.userName()), cell(r.userEmail()),
                    cell(r.role()), cell(r.businessName()), cell(r.hotelId()), cell(r.reference()), cell(r.channel()),
                    r.sharedOn() == null ? "" : r.sharedOn().toString(), cell(r.note()), cell(r.ip()), cell(r.userAgent()), cell(r.recordedByName()), r.id())))
                    .append('\n');
        }
        return sb.toString();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /**
     * The rate the owner's debit box showed, checked against the rate the business is actually debited: a box that said 1.5% can't
     * authorise a 1.2% addendum rate (or the other way round), so a mismatch is a 409 and the page reloads with the right figure.
     */
    private BigDecimal debitRateShown(HospioUserDetails user, AcceptItem item) {
        BigDecimal actual = fees.dailySalesPct(user.getBusinessId());
        if (actual == null) {
            throw new ConflictException("Your business's fee rate could not be checked right now. Try again in a minute");
        }
        BigDecimal shown = item.ratePct() != null ? item.ratePct() : fees.defaultSalesPct();
        if (shown.compareTo(actual) != 0) {
            throw new ConflictException("Your business's daily fee is " + pct(actual) + "% of daily sales, not " + pct(shown)
                    + "%. Reload the page and read it");
        }
        return shown.stripTrailingZeros();
    }

    /** 1.50 → "1.5", 2.000 → "2". */
    static String pct(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private void marketerAccepted(HospioUserDetails user, String version) {
        MarketerProfile p = marketerProfiles.findByUserId(user.getUserId()).orElse(null);
        if (p == null && StringUtils.hasText(user.getEmail())) {
            p = marketerProfiles.findByEmailIgnoreCase(user.getEmail()).orElse(null);
        }
        if (p == null) {
            return;
        }
        if ("ENDED".equals(p.getStatus())) {
            throw new BadRequestException("Your marketer account has ended");
        }
        p.setAgreementVersion(version);
        p.setAgreementAcceptedAt(Instant.now());
        p.setAgreementAcceptedBy(user.getUserId());
        p.setAgreementAcceptedName(user.getFullName());
        marketerProfiles.save(p);
    }

    private LegalAcceptance.LegalAcceptanceBuilder<?, ?> base(HospioUserDetails user, Client client, String businessName) {
        String hotel = user.has(Role.OWNER) || user.getHotelIds() == null || user.getHotelIds().isEmpty() ? null : user.getHotelIds().get(0);
        return LegalAcceptance.builder().userId(user.getUserId()).userName(user.getFullName()).userEmail(user.getEmail()).role(primaryRole(user))
                .businessId(user.getBusinessId()).businessName(businessName).hotelId(hotel).ip(client.ip()).userAgent(client.agent());
    }

    static String primaryRole(HospioUserDetails user) {
        if (user.has(Role.OWNER)) {
            return "OWNER";
        }
        if (user.isMarketer()) {
            return "MARKETER";
        }
        return user.roleSet().stream().filter(STAFF_ROLES::contains).map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse(null);
    }

    private DocView view(LegalDocument d, BigDecimal debitRate) {
        String base = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        BigDecimal rate = DEBIT.equals(d.getDocKey()) && debitRate != null ? debitRate.stripTrailingZeros() : null;
        return new DocView(d.getDocKey(), d.getVersion(), d.getTitle(), d.getSlug(), base + "/legal/" + d.getSlug(), d.getEffectiveFrom(), d.isMaterial(),
                d.getSummary(), rate);
    }

    private RecordView record(LegalAcceptance a) {
        return new RecordView(a.getId(), a.getEvent(), a.getMethod(), a.getDocKey(), title(a.getDocKey(), a.getRatePct()), a.getVersion(), a.getUserId(),
                a.getUserName(), a.getUserEmail(), a.getRole(), a.getBusinessId(), a.getBusinessName(), a.getHotelId(), a.getReference(), a.getChannel(),
                a.getSharedOn(), a.getNote(), a.getIp(), a.getUserAgent(), a.getRecordedByName(), a.getCreatedAt(), a.getRatePct());
    }

    /** A DEBIT record names the rate its box stated; records from before the rate was stored said 1.5% (the box's fixed text then). */
    static String title(String key, BigDecimal ratePct) {
        return switch (key) {
            case TERMS -> "Terms of Service";
            case PRIVACY -> "Privacy Policy";
            case DPA -> "Data Processing Terms";
            case DEBIT -> "Daily debit authority (" + (ratePct == null ? "1.5" : pct(ratePct)) + "%)";
            case DEVICES -> "Devices & equipment terms";
            case SUPPORT -> "Support & services";
            case RPA -> "Referral partner terms";
            case STAFF -> "Staff terms of use";
            default -> key;
        };
    }

    private static String key(String raw) {
        String k = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!KEYS.contains(k)) {
            throw new BadRequestException("Unknown document: " + raw);
        }
        return k;
    }

    static String cell(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r", " ").replace("\n", " ");
        // A cell starting with = + - @ would run as a formula in a spreadsheet.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("legal"), work);
    }

    /**
     * Where the acceptance came from. The web apps call the API from their server and forward the browser's address as {@code X-Client-IP}
     * and its user agent as {@code X-Client-Agent}; a direct call falls back to the proxy headers and its own user agent.
     */
    record Client(String ip, String agent) {
        static Client current() {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
                return new Client(null, null);
            }
            HttpServletRequest req = attrs.getRequest();
            String ip = first(req.getHeader("X-Client-IP"), firstHop(req.getHeader("X-Forwarded-For")), req.getHeader("X-Real-IP"), req.getRemoteAddr());
            String agent = first(req.getHeader("X-Client-Agent"), req.getHeader("User-Agent"));
            return new Client(ip == null ? null : clip(ip, 64), agent == null ? null : clip(agent, 400));
        }

        private static String firstHop(String xff) {
            return xff == null ? null : xff.split(",")[0].trim();
        }

        private static String first(String... values) {
            for (String v : values) {
                if (StringUtils.hasText(v)) {
                    return v.trim();
                }
            }
            return null;
        }

        private static String clip(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max);
        }
    }
}
