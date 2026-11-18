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
import com.hospio.platform.dto.AdminResponses.MarketerInvited;
import com.hospio.platform.dto.PartnerApplicationDtos.ApplicationView;
import com.hospio.platform.dto.PartnerApplicationDtos.ApplyRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.ApproveRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.Approved;
import com.hospio.platform.dto.PartnerApplicationDtos.DeclineRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.PartnerApplicationEvent;
import com.hospio.platform.dto.PartnerApplicationDtos.ReviewDefaults;
import com.hospio.platform.dto.PartnerApplicationDtos.Submission;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.PartnerApplication;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.PartnerApplicationRepository;
import com.hospio.platform.settings.FeeSettings;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * "Apply to be a partner" (docs/plan-marketers.md): the landing site's public form and its review in Marketers · Applications.
 * The form has no login, so everything it sends is checked, cut to length and stored as plain text; a script that fills the
 * hidden field is told "received" and nothing is kept; one address or email can only send a few an hour; and a repeat (an
 * open application, or someone who is already a marketer) gets the same "received" as a new one, so the form never tells
 * anyone whose email is known here. Approving runs the usual marketer invite.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerApplicationService {

    public static final String RECEIVED_MESSAGE = "Thanks — we'll review and email you within 2 working days.";
    public static final String LIMITED_MESSAGE = "You've sent a few applications in a short time. Please try again in an hour, or email us at ";
    static final String ACTIVITY_TOPIC = KafkaTopics.PREFIX + "partner-application";
    static final String NOTICE_KIND = "PARTNER_APPLICATION";

    static final List<String> KINDS = List.of("HOTEL", "LOUNGE", "BAR", "RESTAURANT");
    static final Map<String, String> KIND_WORDS = Map.of("HOTEL", "hotels", "LOUNGE", "lounges", "BAR", "bars", "RESTAURANT", "restaurants");
    static final Set<String> COUNTS = Set.of("1-2", "3-5", "6-10", "10+");
    /** The 36 states and the FCT, as the form lists them. */
    static final Set<String> STATES = Set.of("Abia", "Adamawa", "Akwa Ibom", "Anambra", "Bauchi", "Bayelsa", "Benue", "Borno", "Cross River",
            "Delta", "Ebonyi", "Edo", "Ekiti", "Enugu", "FCT", "Gombe", "Imo", "Jigawa", "Kaduna", "Kano", "Katsina", "Kebbi", "Kogi", "Kwara",
            "Lagos", "Nasarawa", "Niger", "Ogun", "Ondo", "Osun", "Oyo", "Plateau", "Rivers", "Sokoto", "Taraba", "Yobe", "Zamfara");
    static final int NOTE_MAX = 500;

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]{1,64}@[a-z0-9](?:[a-z0-9.-]{0,180}[a-z0-9])?\\.[a-z]{2,24}$");
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M} .'’-]{2,150}$");
    private static final Pattern PLACE = Pattern.compile("^[\\p{L}\\p{M}0-9 .,'’()/-]{2,100}$");
    /** A Nigerian mobile number as people write it: 0803…, 803…, 234803… or +234803…, ten digits after the prefix. */
    private static final Pattern NG_MOBILE = Pattern.compile("^(?:\\+?234|0)?([789][01]\\d{8})$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,29}$");

    private final PartnerApplicationRepository applications;
    private final MarketerProfileRepository profiles;
    private final MarketerService marketers;
    private final PlatformSettingsService settings;
    private final LegalService legal;
    private final DeliveryService deliveries;
    private final PartnerApplicationLimiter limiter;
    private final IdentityClient identity;
    private final EventPublisher events;
    private final ActivityLogService activity;

    // ---- the public form -----------------------------------------------------------------------------

    /**
     * One submission from the landing site. Not one transaction on purpose: the row is saved on its own, so a second request for
     * the same email arriving at the same moment (refused by the unique index) is answered "received" like any repeat.
     */
    public Submission submit(ApplyRequest r, String address, String userAgent) {
        if (r == null) {
            return new Submission(Submission.INVALID, Map.of("fullName", "Enter your full name"));
        }
        if (StringUtils.hasText(r.website())) {
            // The hidden field: a person never fills it. Told "received" so the script learns nothing; nothing is stored or sent.
            log.info("Partner application from {} dropped: the hidden field was filled", address);
            return Submission.received();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String fullName = text(r.fullName());
        if (fullName == null || !NAME.matcher(fullName).matches() || !Phones.meaningfulName(fullName)) {
            errors.put("fullName", fullName == null ? "Enter your full name" : "Enter your name using letters only (up to 150 characters)");
        }
        String email = r.email() == null ? "" : r.email().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            errors.put("email", email.isEmpty() ? "Enter your email address" : "Enter a real email address, like ada@example.com");
        }
        String phone = nigerianPhone(r.phone());
        if (phone == null) {
            errors.put("phone", StringUtils.hasText(r.phone()) ? "Enter a Nigerian phone number, like 0803 555 0192 or +234 803 555 0192" : "Enter your phone number");
        }
        String city = text(r.city());
        if (city == null || !PLACE.matcher(city).matches()) {
            errors.put("city", city == null ? "Enter your city" : "Enter the name of your city");
        }
        String state = text(r.state());
        if (state == null || !STATES.contains(state)) {
            errors.put("state", "Choose your state");
        }
        Set<String> picked = new LinkedHashSet<>();
        boolean unknownKind = false;
        if (r.businessKinds() != null) {
            for (String k : r.businessKinds()) {
                String kind = k == null ? "" : k.trim().toUpperCase(Locale.ROOT);
                if (KINDS.contains(kind)) {
                    picked.add(kind);
                } else {
                    unknownKind = true;
                }
            }
        }
        if (picked.isEmpty() || unknownKind) {
            errors.put("businessKinds", "Tick at least one kind of business you know");
        }
        String count = text(r.businessCount());
        if (count == null || !COUNTS.contains(count)) {
            errors.put("businessCount", "Choose roughly how many businesses you know");
        }
        String note = note(r.networkNote());
        if (note != null && note.length() > NOTE_MAX) {
            errors.put("networkNote", "Keep it to " + NOTE_MAX + " characters");
        }
        String company = text(r.companyName());
        if (company != null && company.length() > 150) {
            errors.put("companyName", "Keep the company name to 150 characters");
        }
        if (!Boolean.TRUE.equals(r.acceptedTerms())) {
            errors.put("acceptedTerms", "Tick the box to say you have read the Referral partner terms and the Privacy Policy");
        }
        if (!errors.isEmpty()) {
            return new Submission(Submission.INVALID, errors);
        }
        if (!limiter.allowAddress(address) || !limiter.allowEmail(email)) {
            log.info("Partner application from {} refused: over the hour's limit", address);
            return new Submission(Submission.LIMITED, Map.of());
        }
        List<String> kinds = KINDS.stream().filter(picked::contains).toList();
        return TenantContext.runWith(Tenant.system("partner-applications"), () -> {
            if (applications.existsByEmailIgnoreCaseAndStatus(email, PartnerApplication.PENDING) || profiles.findByEmailIgnoreCase(email).isPresent()) {
                // Same answer as a new application: the form never says an email is known here. Nothing new is stored or sent.
                log.info("Partner application from {} not stored: an open application or a marketer already has that email", address);
                return Submission.received();
            }
            PartnerApplication a;
            try {
                a = applications.saveAndFlush(PartnerApplication.builder().fullName(fullName).email(email).phone(phone).city(city).state(state)
                        .businessKinds(String.join(",", kinds)).businessCount(count).networkNote(note).companyName(company)
                        .status(PartnerApplication.PENDING).ip(cut(oneLine(address), 64)).userAgent(cut(oneLine(userAgent), 300))
                        .rpaVersion(version(r.rpaVersion(), "RPA")).privacyVersion(version(r.privacyVersion(), "PRIVACY")).build());
            } catch (DataIntegrityViolationException e) {
                log.info("Partner application from {} not stored: another request for the same email got there first", address);
                return Submission.received();
            }
            log.info("Partner application {} stored ({}, {})", a.getId(), a.getCity(), a.getBusinessKinds());
            deliveries.send(Delivery.email(email, "We received your Hospio360 partner application", receivedEmail(a), Delivery.Kind.NOTICE,
                    a.getId() + ":received"));
            tellStaff(a);
            return Submission.received();
        });
    }

    public String supportEmail() {
        try {
            String s = TenantContext.runWith(Tenant.system("partner-applications"), () -> settings.general().supportEmail());
            return StringUtils.hasText(s) ? s : "hello@oyeksglobal.com";
        } catch (RuntimeException e) {
            return "hello@oyeksglobal.com";
        }
    }

    /** The bell of every super admin and every support admin (the people who invite marketers). Never fails the submission. */
    private void tellStaff(PartnerApplication a) {
        try {
            Set<String> who = new LinkedHashSet<>();
            for (String role : List.of(Role.SUPER_ADMIN.name(), Role.SUPPORT.name())) {
                identity.platformStaff(role).forEach(c -> who.add(c.id()));
            }
            who.remove(null);
            if (who.isEmpty()) {
                log.warn("Partner application {}: nobody to tell (no super admin or support admin found)", a.getId());
                return;
            }
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind(NOTICE_KIND).title("New partner application")
                    .body(a.getFullName() + " (" + a.getCity() + ", " + a.getState() + ") applied to be a referral partner: knows "
                            + countWords(a.getBusinessCount()) + " " + kindWords(a) + ".")
                    .link("/dashboard/marketers?tab=applications&app=" + a.getId()).userIds(new ArrayList<>(who)).build());
        } catch (RuntimeException e) {
            log.warn("Partner application {}: the team was not told ({})", a.getId(), e.getMessage());
        }
    }

    // ---- review --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageWithCounts<ApplicationView> list(String status, String q, Integer page, Integer size) {
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        Page<PartnerApplication> result = applications.findAll(spec(st, q), Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        Map<String, String> names = new LinkedHashMap<>();
        List<String> ids = result.getContent().stream().map(PartnerApplication::getMarketerProfileId).filter(StringUtils::hasText).distinct().toList();
        if (!ids.isEmpty()) {
            profiles.findAllById(ids).forEach(p -> names.put(p.getId(), p.getFullName()));
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", applications.count(spec(null, q)));
        for (String s : List.of(PartnerApplication.PENDING, PartnerApplication.APPROVED, PartnerApplication.DECLINED)) {
            counts.put(s, applications.count(spec(s, q)));
        }
        // The tab's badge: everything waiting, whatever the search says.
        counts.put("pendingTotal", applications.countByStatus(PartnerApplication.PENDING));
        return PageWithCounts.of(result, a -> ApplicationView.of(a, names.get(a.getMarketerProfileId())), counts, Map.of());
    }

    static Specification<PartnerApplication> spec(String status, String q) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(cb.like(cb.lower(root.get("fullName")), like, '\\'), cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(root.get("phone"), like, '\\'), cb.like(cb.lower(root.get("city")), like, '\\'),
                        cb.like(cb.lower(root.get("state")), like, '\\'), cb.like(cb.lower(cb.coalesce(root.get("companyName"), "")), like, '\\')));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public ApplicationView get(String id) {
        return view(applications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Application not found")));
    }

    /** What the approve form opens with: the share and months from Settings · Fees, and what this admin may do. */
    @Transactional(readOnly = true)
    public ReviewDefaults defaults() {
        HospioUserDetails user = CurrentUser.require();
        FeeSettings fees = settings.fees();
        return new ReviewDefaults(fees.marketerSharePct(), fees.marketerMonths(), canDecide(user), canChangeTerms(user));
    }

    /** The people who invite marketers in the admin portal: super admins and support. */
    static boolean canDecide(HospioUserDetails user) {
        return user.isPlatform() && (user.has(Role.SUPER_ADMIN) || user.has(Role.SUPPORT));
    }

    /** A share or term other than the standard one is a money decision: super admin or finance. */
    static boolean canChangeTerms(HospioUserDetails user) {
        return user.isPlatform() && (user.has(Role.SUPER_ADMIN) || user.has(Role.FINANCE_ADMIN));
    }

    /**
     * Approve: the usual marketer invite ({@link MarketerService#invite}) with the application's name, email and phone (or what the
     * admin corrected in the form), then the application is APPROVED and linked to the marketer it made. One transaction with the
     * row locked, so an application is decided once.
     */
    @Transactional
    public Approved approve(String id, ApproveRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (!canDecide(user)) {
            throw new ForbiddenException("Only a super admin or support can approve a partner application");
        }
        PartnerApplication a = pending(id);
        ApproveRequest r = request == null ? new ApproveRequest(null, null, null, null, null) : request;
        FeeSettings fees = settings.fees();
        BigDecimal share = r.sharePct() == null ? fees.marketerSharePct() : r.sharePct();
        int months = r.months() == null ? fees.marketerMonths() : r.months();
        boolean standard = share.compareTo(fees.marketerSharePct()) == 0 && months == fees.marketerMonths();
        if (!standard && !canChangeTerms(user)) {
            throw new ForbiddenException("Only a super admin or finance can change the share or the months. Approve with the standard "
                    + fees.marketerSharePct().stripTrailingZeros().toPlainString() + "% for " + fees.marketerMonths() + " months, or ask them");
        }
        if (share.signum() <= 0 || share.compareTo(new BigDecimal("100")) > 0) {
            throw new BadRequestException("The share is a percentage above 0, up to 100");
        }
        if (months < 1 || months > 120) {
            throw new BadRequestException("The months are 1 to 120");
        }
        String fullName = StringUtils.hasText(r.fullName()) ? text(r.fullName()) : a.getFullName();
        String email = StringUtils.hasText(r.email()) ? r.email().trim().toLowerCase(Locale.ROOT) : a.getEmail();
        if (fullName == null || !Phones.meaningfulName(fullName)) {
            throw new BadRequestException("Enter the marketer's full name");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw new BadRequestException("Enter a real email address");
        }
        // Not sent: the applicant's own number. Sent empty: invite without a phone (it is already on another account).
        String phone = r.phone() == null ? a.getPhone() : Phones.clean(r.phone(), "Phone");
        MarketerInvited invited = marketers.invite(new PlatformRequests.MarketerInviteRequest(fullName, email, phone, share, months));
        a.setStatus(PartnerApplication.APPROVED);
        a.setDecidedAt(Instant.now());
        a.setDecidedBy(user.getUserId());
        a.setDecidedByName(user.getFullName());
        a.setMarketerProfileId(invited.profile().getId());
        applications.save(a);
        record(a, user, "APPROVED", "invited as a marketer at " + share.stripTrailingZeros().toPlainString() + "% for " + months + " months ("
                + email + ")");
        return new Approved(ApplicationView.of(a, invited.profile().getFullName()), invited);
    }

    /** Decline with a reason (kept, and in the activity log); the applicant is emailed, with the reason only when the admin chose to share it. */
    @Transactional
    public ApplicationView decline(String id, DeclineRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (!canDecide(user)) {
            throw new ForbiddenException("Only a super admin or support can decline a partner application");
        }
        String reason = request == null ? null : note(request.reason());
        if (reason == null || reason.length() < 5) {
            throw new BadRequestException("Say why the application is declined (a few words; it is kept with the application)");
        }
        if (reason.length() > 500) {
            throw new BadRequestException("Keep the reason to 500 characters");
        }
        PartnerApplication a = pending(id);
        boolean share = request != null && Boolean.TRUE.equals(request.shareReason());
        a.setStatus(PartnerApplication.DECLINED);
        a.setDecidedAt(Instant.now());
        a.setDecidedBy(user.getUserId());
        a.setDecidedByName(user.getFullName());
        a.setDeclineReason(reason);
        a.setDeclineReasonShared(share);
        applications.save(a);
        record(a, user, "DECLINED", reason + (share ? " (reason given to the applicant)" : ""));
        deliveries.send(Delivery.email(a.getEmail(), "Your Hospio360 partner application", declinedEmail(a), Delivery.Kind.NOTICE, a.getId() + ":declined"));
        return view(a);
    }

    private PartnerApplication pending(String id) {
        PartnerApplication a = applications.findForDecision(id).orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (!PartnerApplication.PENDING.equals(a.getStatus())) {
            throw new BadRequestException("This application was already " + a.getStatus().toLowerCase(Locale.ROOT)
                    + (a.getDecidedByName() == null ? "" : " by " + a.getDecidedByName()));
        }
        return a;
    }

    private ApplicationView view(PartnerApplication a) {
        String name = a.getMarketerProfileId() == null ? null : profiles.findById(a.getMarketerProfileId()).map(MarketerProfile::getFullName).orElse(null);
        return ApplicationView.of(a, name);
    }

    /** Changes by Hospio360 · Admin activity: who decided what, with the reason. */
    private void record(PartnerApplication a, HospioUserDetails user, String decision, String detail) {
        activity.record(ACTIVITY_TOPIC, PartnerApplicationEvent.builder().eventId(UUID.randomUUID().toString()).actorId(user.getUserId())
                .actorName("Hospio360 · " + user.getFullName()).occurredAt(Instant.now()).applicationId(a.getId()).applicantName(a.getFullName())
                .decision(decision).detail(detail).build());
    }

    // ---- emails --------------------------------------------------------------------------------------

    String receivedEmail(PartnerApplication a) {
        return "Hello " + firstName(a.getFullName()) + ",\n\nThank you for applying to the Hospio360 referral partner programme. We have your "
                + "application and will review it and email you within 2 working days.\n\nWhat you told us:\n"
                + "Name: " + a.getFullName() + "\n"
                + (a.getCompanyName() == null ? "" : "Company: " + a.getCompanyName() + "\n")
                + "Phone: " + a.getPhone() + "\n"
                + "Where: " + a.getCity() + ", " + a.getState() + "\n"
                + "Businesses you know: " + kindWords(a) + " (" + countWords(a.getBusinessCount()) + ")\n\n"
                + "If we approve it, you will get a second email with a link to set up your partner login. If you did not apply, "
                + "ignore this email or tell us at " + supportEmail() + ".\n\nHospio360\n";
    }

    String declinedEmail(PartnerApplication a) {
        return "Hello " + firstName(a.getFullName()) + ",\n\nThank you for your interest in the Hospio360 referral partner programme, and for the "
                + "time you took to apply. We have reviewed your application and are not able to take it forward at this time.\n\n"
                + (a.isDeclineReasonShared() && a.getDeclineReason() != null ? "A note from our team: " + a.getDeclineReason() + "\n\n" : "")
                + "You are welcome to apply again in future. If you have a question, write to us at " + supportEmail() + ".\n\nHospio360\n";
    }

    // ---- cleaning what the form sent -----------------------------------------------------------------

    /** +234 and the national number, or null when it isn't a Nigerian number. */
    static String nigerianPhone(String raw) {
        if (!StringUtils.hasText(raw) || raw.length() > 30 || !raw.trim().matches("^\\+?[0-9 ()-]+$")) {
            return null;
        }
        String d = (raw.trim().startsWith("+") ? "+" : "") + Phones.digits(raw);
        java.util.regex.Matcher m = NG_MOBILE.matcher(d);
        if (!m.matches()) {
            return null;
        }
        String national = m.group(1);
        return national.matches("^(\\d)\\1*$") || national.substring(3).matches("^(\\d)\\1*$") ? null : "+234" + national;
    }

    /** One line of plain text: no angle brackets (so never markup), no control characters, single spaces; blank is null. */
    static String text(String raw) {
        String s = oneLine(raw);
        return s == null || s.isEmpty() ? null : s;
    }

    private static String oneLine(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[<>]", "").replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Free text that may have line breaks: no angle brackets or control characters, at most one blank line in a row. */
    static String note(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[<>]", "").replaceAll("[\\p{Cntrl}\\p{Cf}&&[^\\n]]", " ")
                .replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    /** The version the page said it showed when it is one of ours for that document; otherwise the one in force now. */
    private String version(String shown, String key) {
        String current = legal.currentVersion(key);
        if (shown != null && VERSION.matcher(shown.trim()).matches()
                && legal.allVersions().stream().anyMatch(d -> key.equals(d.getDocKey()) && shown.trim().equals(d.getVersion()))) {
            return shown.trim();
        }
        return current;
    }

    private static String firstName(String fullName) {
        return fullName.trim().split("\\s+")[0];
    }

    static String kindWords(PartnerApplication a) {
        List<String> words = new ArrayList<>();
        for (String k : a.getBusinessKinds().split(",")) {
            words.add(KIND_WORDS.getOrDefault(k, k.toLowerCase(Locale.ROOT)));
        }
        if (words.size() <= 1) {
            return String.join("", words);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    static String countWords(String count) {
        return "10+".equals(count) ? "more than 10" : count.replace("-", " to ");
    }
}
