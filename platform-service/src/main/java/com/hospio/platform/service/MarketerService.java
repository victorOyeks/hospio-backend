package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.AdminResponses.CommissionRow;
import com.hospio.platform.dto.AdminResponses.CommissionStatement;
import com.hospio.platform.dto.AdminResponses.MarketerBusiness;
import com.hospio.platform.dto.AdminResponses.MarketerDetail;
import com.hospio.platform.dto.AdminResponses.MarketerInvited;
import com.hospio.platform.dto.AdminResponses.MarketerLogin;
import com.hospio.platform.dto.AdminResponses.MarketerMe;
import com.hospio.platform.dto.AdminResponses.MarketerRow;
import com.hospio.platform.dto.AdminResponses.MarketerTotals;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.Referral;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.repository.ReferralRepository;
import com.hospio.platform.settings.FeeSettings;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Marketers: the profile (a person with a MARKETER login), their referrals, the per-business agreements that
 * open when a referred business goes LIVE, and the commissions those earn. The marketer portal reads the same
 * tables as the system, filtered by the caller's profile, because a marketer token has no business and no
 * platform powers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketerService {

    static final Set<String> REFERRAL_STAGES = Set.of("LEAD", "CONTACTED", "ONBOARDING", "LIVE", "LOST");
    static final Set<String> PROFILE_STATUSES = Set.of("PENDING", "ACTIVE", "ENDED");
    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.of("Africa/Lagos"));
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MarketerProfileRepository profiles;
    private final ReferralRepository referrals;
    private final MarketerRepository marketers;
    private final CommissionRepository commissions;
    private final IdentityClient identity;
    private final TenantClient tenant;
    private final BusinessNames businessNames;
    private final PlatformSettingsService settings;
    private final DeliveryService deliveries;
    private final LegalService legal;
    private final jakarta.persistence.EntityManager em;
    private final CommissionService commissionService;
    private final com.hospio.platform.repository.MarketerStatementRepository statements;
    private final ChangeService changes;

    /** The Referral Partner Agreement version marketers accept in the portal (Downloads template 10, docs/plan-marketers.md). */
    public static final String FIRST_AGREEMENT_VERSION = "RPA-2026-11";
    static final java.util.regex.Pattern NUBAN = java.util.regex.Pattern.compile("\\d{10}");

    @Value("${hospio.apps.admin-url:https://admin-staging.hospio360.com}")
    private String adminUrl;

    // ---- platform: profiles --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MarketerRow> profiles(String status, String q) {
        String like = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;
        List<MarketerRow> out = new ArrayList<>();
        for (MarketerProfile p : profiles.findAllByOrderByFullNameAsc()) {
            if (StringUtils.hasText(status) && !status.equalsIgnoreCase(p.getStatus())) {
                continue;
            }
            if (like != null && !(contains(p.getFullName(), like) || contains(p.getEmail(), like) || contains(p.getPhone(), like) || contains(p.getReferralCode(), like))) {
                continue;
            }
            out.add(row(p));
        }
        return out;
    }

    /** Creates the MARKETER login through identity, then the profile; the login's deliveries are built here. */
    @Transactional
    public MarketerInvited invite(PlatformRequests.MarketerInviteRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (profiles.findByEmailIgnoreCase(email).isPresent()) {
            throw new ConflictException("A marketer with that email already exists");
        }
        FeeSettings fees = settings.fees();
        IdentityClient.CreatedLogin login = identity.createMarketer(request.fullName().trim(), email, request.phone());
        MarketerProfile p = profiles.save(MarketerProfile.builder().userId(login.userId()).fullName(request.fullName().trim()).email(email)
                .phone(request.phone()).status("PENDING")
                .sharePct(request.sharePct() == null ? fees.marketerSharePct() : request.sharePct())
                .months(request.months() == null ? fees.marketerMonths() : request.months())
                .referralCode(newReferralCode()).build());
        // identity already published (and, with echo on, returned) the login's deliveries; composing here is only for a build without them
        List<Delivery> sent = login.deliveries() != null ? login.deliveries() : deliveries.send(loginDeliveries(p, login));
        return new MarketerInvited(p, new MarketerLogin(login.userId(), login.temporaryPassword(), login.temporaryPasswordExpiresAt(),
                login.inviteToken(), login.inviteExpiresAt()), sent);
    }

    List<Delivery> loginDeliveries(MarketerProfile p, IdentityClient.CreatedLogin login) {
        List<Delivery> out = new ArrayList<>();
        String app = adminUrl.endsWith("/") ? adminUrl.substring(0, adminUrl.length() - 1) : adminUrl;
        if (login.temporaryPassword() != null) {
            out.add(Delivery.email(p.getEmail(), "Your Hospio360 marketer login",
                    "Hello " + p.getFullName() + ",\n\nWelcome to the Hospio360 marketer programme. A login was set up for you.\n\n"
                            + "Email: " + p.getEmail() + "\nTemporary password: " + login.temporaryPassword() + "\n"
                            + (login.temporaryPasswordExpiresAt() == null ? "" : "It works until " + WHEN.format(login.temporaryPasswordExpiresAt()) + " and must be changed on first sign-in.\n")
                            + "\nSign in at " + app + "\nYour referral code is " + p.getReferralCode() + ".\n",
                    Delivery.Kind.TEMP_PASSWORD, p.getUserId()));
        }
        if (login.inviteToken() != null) {
            String link = app + "/accept-invite?token=" + login.inviteToken();
            out.add(Delivery.email(p.getEmail(), "You're invited to Hospio360",
                    "Hello " + p.getFullName() + ",\n\nOpen this link to choose your password and sign in to the marketer portal:\n" + link + "\n"
                            + (login.inviteExpiresAt() == null ? "" : "\nThe link works until " + WHEN.format(login.inviteExpiresAt()) + ".\n"),
                    Delivery.Kind.INVITE, p.getUserId()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public MarketerDetail detail(String profileId) {
        MarketerProfile p = findProfile(profileId);
        List<Commission> cs = commissions.findByProfileIdOrderByMonthDesc(p.getId());
        Money3 m = money(p.getId(), cs);
        return new MarketerDetail(p, marketers.findByProfileIdOrderByCreatedAtDesc(p.getId()), referrals.findByProfileIdOrderBySentAtDesc(p.getId()),
                cs.stream().map(c -> row(c, p.getFullName())).toList(), m.earned(), m.due(), m.paid(), referred(p.getId()), businessesByStage(p.getId()),
                commissionService.openLines(p.getId()), commissionService.forProfile(p.getId()), agreementVersion());
    }

    @Transactional
    public MarketerProfile update(String profileId, PlatformRequests.MarketerUpdateRequest request) {
        MarketerProfile p = findProfile(profileId);
        boolean wasNotEnded = !"ENDED".equals(p.getStatus());
        if (request.sharePct() != null) {
            if (request.sharePct().signum() < 0 || request.sharePct().compareTo(new BigDecimal("100")) > 0) {
                throw new BadRequestException("sharePct must be between 0 and 100");
            }
            p.setSharePct(request.sharePct());
        }
        if (request.months() != null) {
            if (request.months() < 1) {
                throw new BadRequestException("months must be at least 1");
            }
            p.setMonths(request.months());
        }
        if (StringUtils.hasText(request.status())) {
            String s = request.status().toUpperCase(Locale.ROOT);
            if (!PROFILE_STATUSES.contains(s)) {
                throw new BadRequestException("status must be PENDING, ACTIVE or ENDED");
            }
            p.setStatus(s);
            if ("ENDED".equals(s)) {
                p.setEndedAt(Instant.now());
                for (Marketer m : marketers.findByProfileIdOrderByCreatedAtDesc(p.getId())) {
                    if ("ACTIVE".equals(m.getStatus())) {
                        m.setStatus("ENDED");
                        m.setEndedOn(LocalDate.now(LAGOS));
                        m.setEndedReason("Marketer ended");
                        marketers.save(m);
                    }
                }
            } else {
                p.setEndedAt(null);
                if ("ACTIVE".equals(s) && p.getJoinedAt() == null) {
                    p.setJoinedAt(Instant.now());
                }
            }
        }
        boolean ending = "ENDED".equals(p.getStatus()) && wasNotEnded;
        MarketerProfile saved = profiles.save(p);
        if (ending) {
            // Their carried-over balance and open lines are paid out on a final statement, whatever the minimum (docs/plan-marketers.md).
            commissionService.makeFinalStatement(saved.getId());
        }
        return saved;
    }

    // ---- platform: referrals and commissions ---------------------------------------------------------

    /** Referrals, newest first; size 1 to 100 (25 by default). {@code counts} has every stage (and {@code all}) for the profile. */
    @Transactional(readOnly = true)
    public PageWithCounts<Referral> referrals(String profileId, String stage, Integer page, Integer size) {
        Page<Referral> result = referrals.findAll(referralSpec(profileId, stage), Paging.of(page, size,
                Sort.by(Sort.Direction.DESC, "sentAt").and(Sort.by("id"))));
        return PageWithCounts.of(result, r -> r, referralCounts(profileId), Map.of());
    }

    static Specification<Referral> referralSpec(String profileId, String stage) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(profileId)) {
                ps.add(cb.equal(root.get("profileId"), profileId));
            }
            if (StringUtils.hasText(stage)) {
                ps.add(cb.equal(root.get("stage"), stage.toUpperCase(Locale.ROOT)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Map<String, Long> referralCounts(String profileId) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("all", referrals.count(referralSpec(profileId, null)));
        for (String s : List.of("LEAD", "CONTACTED", "ONBOARDING", "LIVE", "LOST")) {
            counts.put(s, referrals.count(referralSpec(profileId, s)));
        }
        return counts;
    }

    @Transactional
    public Referral setReferralStage(String id, PlatformRequests.ReferralStageRequest request) {
        Referral r = referrals.findById(id).orElseThrow(() -> new ResourceNotFoundException("Referral not found"));
        String stage = request.stage().toUpperCase(Locale.ROOT);
        if (!REFERRAL_STAGES.contains(stage)) {
            throw new BadRequestException("stage must be LEAD, CONTACTED, ONBOARDING, LIVE or LOST");
        }
        r.setStage(stage);
        if (request.note() != null) {
            r.setNote(request.note());
        }
        return referrals.save(r);
    }

    /**
     * Commissions, latest month first; size 1 to 100 (25 by default). {@code counts}: all, DUE, WAITING, PAID for the profile and month
     * (not the status); {@code totals}: gross, wht and net of the filtered range, and what is due, waiting and paid of it.
     */
    @Transactional(readOnly = true)
    public PageWithCounts<CommissionRow> commissions(String profileId, String status, String month, Integer page, Integer size) {
        Page<Commission> result = commissions.findAll(commissionSpec(profileId, status, month), Paging.of(page, size,
                Sort.by(Sort.Direction.DESC, "month").and(Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by("id"))));
        Map<String, String> marketerNames = new HashMap<>();
        return PageWithCounts.of(result, c -> row(c, marketerName(c, marketerNames)), commissionCounts(profileId, month),
                commissionTotals(profileId, status, month));
    }

    static Specification<Commission> commissionSpec(String profileId, String status, String month) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(profileId)) {
                ps.add(cb.equal(root.get("profileId"), profileId));
            }
            if (StringUtils.hasText(status)) {
                ps.add(cb.equal(root.get("status"), status.toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(month)) {
                ps.add(cb.equal(root.get("month"), month));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Map<String, Long> commissionCounts(String profileId, String month) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("all", commissions.count(commissionSpec(profileId, null, month)));
        for (String s : List.of("DUE", "WAITING", "PAID")) {
            counts.put(s, commissions.count(commissionSpec(profileId, s, month)));
        }
        return counts;
    }

    /** Money over the filtered range, summed in the database. */
    private Map<String, BigDecimal> commissionTotals(String profileId, String status, String month) {
        jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        jakarta.persistence.criteria.Root<Commission> r = cq.from(Commission.class);
        jakarta.persistence.criteria.Expression<BigDecimal> net = r.get("net");
        cq.multiselect(cb.coalesce(cb.sum(r.<BigDecimal>get("gross")), BigDecimal.ZERO), cb.coalesce(cb.sum(r.<BigDecimal>get("wht")), BigDecimal.ZERO),
                cb.coalesce(cb.sum(net), BigDecimal.ZERO),
                cb.coalesce(cb.sum(cb.<BigDecimal>selectCase().when(cb.equal(r.get("status"), "DUE"), net).otherwise(BigDecimal.ZERO)), BigDecimal.ZERO),
                cb.coalesce(cb.sum(cb.<BigDecimal>selectCase().when(cb.equal(r.get("status"), "WAITING"), net).otherwise(BigDecimal.ZERO)), BigDecimal.ZERO),
                cb.coalesce(cb.sum(cb.<BigDecimal>selectCase().when(cb.equal(r.get("status"), "PAID"), net).otherwise(BigDecimal.ZERO)), BigDecimal.ZERO))
                .where(commissionSpec(profileId, status, month).toPredicate(r, cq, cb));
        Object[] t = em.createQuery(cq).getSingleResult();
        Map<String, BigDecimal> totals = new java.util.LinkedHashMap<>();
        String[] keys = {"gross", "wht", "net", "due", "waiting", "paid"};
        for (int i = 0; i < keys.length; i++) {
            totals.put(keys[i], com.hospio.common.util.Money.of(t[i] == null ? BigDecimal.ZERO : new BigDecimal(t[i].toString())));
        }
        return totals;
    }

    @Transactional
    public Commission pay(String id, PlatformRequests.PayCommissionRequest request) {
        Commission c = commissions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Commission not found"));
        if ("PAID".equals(c.getStatus())) {
            throw new BadRequestException("Already paid");
        }
        if (c.getSource() != null) {
            throw new BadRequestException("Commission on collected fees is paid through the marketer's monthly statement (Statements)");
        }
        c.setStatus("PAID");
        c.setPaidAt(Instant.now());
        c.setPaidOn(request == null || request.paidOn() == null ? LocalDate.now() : request.paidOn());
        c.setPaidReference(request == null ? null : request.reference());
        return commissions.save(c);
    }

    // ---- the marketer portal -------------------------------------------------------------------------

    @Transactional
    public MarketerMe me() {
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            List<Commission> cs = commissions.findByProfileIdOrderByMonthDesc(p.getId());
            Money3 m = money(p.getId(), cs);
            FeeSettings fees = settings.fees();
            return new MarketerMe(p, new MarketerTotals(m.earned(), m.due(), m.paid(), referred(p.getId()), (int) referrals.countByProfileId(p.getId()),
                    m.open(), m.carried(), businessesByStage(p.getId())), agreementVersion(), agreementVersion().equals(p.getAgreementVersion()),
                    commissionService.minimumPayout(), fees.whtPct());
        });
    }

    /**
     * The marketer's bank details: bank, 10-digit NUBAN account number, account name. Changing saved details needs a note (why), and
     * every save clears finance's check, so a statement can't be approved until finance has checked the new details.
     */
    @Transactional
    public MarketerProfile updateBank(PlatformRequests.BankRequest request) {
        String bank = request.bankName().trim();
        String account = request.bankAccount().replaceAll("\\s", "");
        String name = request.bankAccountName().trim();
        if (!NUBAN.matcher(account).matches()) {
            throw new BadRequestException("The account number is the 10-digit NUBAN");
        }
        if (name.length() < 3 || !name.matches(".*[A-Za-z].*")) {
            throw new BadRequestException("Enter the account name as the bank shows it");
        }
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            boolean had = StringUtils.hasText(p.getBankAccount());
            boolean same = had && bank.equals(p.getBankName()) && account.equals(p.getBankAccount()) && name.equals(p.getBankAccountName());
            if (same) {
                return p;
            }
            if (had && (request.note() == null || request.note().trim().length() < 5)) {
                throw new BadRequestException("Say why the bank details are changing; finance checks them again before the next payment");
            }
            p.setBankName(bank);
            p.setBankAccount(account);
            p.setBankAccountName(name);
            p.setBankSavedAt(Instant.now());
            p.setBankChangeNote(had ? request.note().trim() : null);
            p.setBankCheckedAt(null);
            p.setBankCheckedByName(null);
            return profiles.save(p);
        });
    }

    /** Finance checked the marketer's bank details (account name matches the bank's): statements can be approved again. */
    @Transactional
    public MarketerProfile markBankChecked(String profileId) {
        HospioUserDetails user = CurrentUser.require();
        MarketerProfile p = findProfile(profileId);
        if (!StringUtils.hasText(p.getBankAccount())) {
            throw new BadRequestException(p.getFullName() + " hasn't added bank details yet");
        }
        p.setBankCheckedAt(Instant.now());
        p.setBankCheckedByName(user.getFullName());
        return profiles.save(p);
    }

    /**
     * The marketer accepts the Referral Partner Agreement (the version in force) in the portal. Recorded by the one acceptance system
     * ({@link LegalService#accept}, method PORTAL, with IP and browser), which also keeps the version on the profile.
     */
    @Transactional
    public MarketerProfile acceptAgreement(String version) {
        String current = agreementVersion();
        if (!current.equals(version)) {
            throw new BadRequestException("Accept the current agreement (" + current + "); reload the page");
        }
        MarketerProfile before = asSystem(this::myProfile);
        if ("ENDED".equals(before.getStatus())) {
            throw new BadRequestException("Your marketer account has ended");
        }
        legal.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem(LegalService.RPA, current)), "PORTAL"));
        return asSystem(this::myProfile);
    }

    /** The Referral Partner Agreement version in force (legal records); the first published one when none is found. */
    String agreementVersion() {
        String v = legal.currentVersion(LegalService.RPA);
        return v == null ? FIRST_AGREEMENT_VERSION : v;
    }

    /** My statements, newest first, and what goes on the next one. */
    @Transactional
    public MyStatements myStatements() {
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            return new MyStatements(commissionService.forProfile(p.getId()), commissionService.openLines(p.getId()), commissionService.minimumPayout());
        });
    }

    public record MyStatements(List<CommissionService.StatementRow> statements, List<CommissionService.LineView> nextStatement, BigDecimal minimumPayout) {
    }

    @Transactional
    public CommissionService.StatementDetail myStatementDetail(String id) {
        return asSystem(() -> commissionService.detailFor(myProfile(), id));
    }

    @Transactional
    public CommissionService.StatementDetail queryStatement(String id, String note) {
        MarketerProfile p = asSystem(this::myProfile);
        return commissionService.query(p, id, note);
    }

    /** A referral code's marketer (sign-up with ?ref=); only an ACTIVE marketer who accepted the agreement can refer. */
    @Transactional(readOnly = true)
    public java.util.Optional<ReferralCodeInfo> referralCode(String code) {
        if (!StringUtils.hasText(code)) {
            return java.util.Optional.empty();
        }
        return asSystem(() -> profiles.findByReferralCode(code.trim().toUpperCase(Locale.ROOT)).map(p -> new ReferralCodeInfo(p.getId(), p.getReferralCode(),
                p.getFullName(), p.getStatus(), "ACTIVE".equals(p.getStatus()) && agreementVersion().equals(p.getAgreementVersion()))));
    }

    public record ReferralCodeInfo(String profileId, String code, String fullName, String status, boolean canRefer) {
    }

    /**
     * Who referred a business (admin, onboarding step details or the business page). The old marketer's active agreement ends today;
     * when the business is live (or at risk) the new marketer's agreement opens today (keeping the business's 12-month window, so it
     * isn't extended); before go-live it opens when the business goes live. Recorded in Changes by Hospio360 (the audit log).
     */
    @Transactional
    public MarketerChange changeMarketer(String businessId, String profileId, String reason) {
        HospioUserDetails user = CurrentUser.require();
        TenantClient.Business b = tenant.business(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        String to = StringUtils.hasText(profileId) ? profileId : null;
        MarketerProfile next = to == null ? null : findProfile(to);
        if (next != null && "ENDED".equals(next.getStatus())) {
            throw new BadRequestException(next.getFullName() + "'s marketer account has ended; choose an active marketer");
        }
        String from = StringUtils.hasText(b.marketerId()) ? b.marketerId() : null;
        if (java.util.Objects.equals(from, to)) {
            throw new BadRequestException(to == null ? "No marketer is set on this business" : next.getFullName() + " is already set on this business");
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 5) {
            throw new BadRequestException("Say why the marketer is changing (it goes in the audit log)");
        }
        String fromName = from == null ? "nobody" : profiles.findById(from).map(MarketerProfile::getFullName).orElse(from);
        String toName = next == null ? "nobody" : next.getFullName();
        if (!tenant.setMarketer(businessId, to)) {
            throw new com.hospio.common.exception.ExternalServiceException("Couldn't save the marketer on the business right now; try again", null);
        }
        LocalDate today = LocalDate.now(LAGOS);
        List<Marketer> ended = new ArrayList<>();
        Marketer opened = asSystem(() -> {
            for (Marketer m : marketers.findByBusinessId(businessId)) {
                if ("ACTIVE".equals(m.getStatus()) && (to == null || !to.equals(m.getProfileId()))) {
                    m.setStatus("ENDED");
                    m.setEndedOn(today);
                    m.setEndedReason("Marketer changed to " + toName + " by " + user.getFullName() + ": " + reason.trim());
                    ended.add(marketers.save(m));
                }
            }
            if (next != null && java.util.Set.of("LIVE", "AT_RISK").contains(b.stage())) {
                return openAgreement(b, next, today);
            }
            return null;
        });
        changes.record(new PlatformRequests.PlatformChangeRequest(businessId, null, "Marketer",
                "Referred by changed from " + fromName + " to " + toName + (ended.isEmpty() ? "" : "; the earlier agreement ended " + today)
                        + (opened == null ? "" : "; " + toName + "'s agreement opened " + today),
                reason.trim(), "{\"marketerId\":" + (from == null ? "null" : "\"" + from + "\"") + "}",
                "{\"marketerId\":" + (to == null ? "null" : "\"" + to + "\"") + "}"));
        return new MarketerChange(businessId, from, fromName, to, toName, ended, opened);
    }

    public record MarketerChange(String businessId, String fromProfileId, String fromName, String toProfileId, String toName, List<Marketer> ended,
                                 Marketer opened) {
    }

    /** A new ACTIVE agreement from {@code start} with the profile's share and months, keeping the business's window if it has one. */
    Marketer openAgreement(TenantClient.Business b, MarketerProfile p, LocalDate start) {
        FeeSettings fees = settings.fees();
        LocalDate windowStart = null;
        for (Marketer m : marketers.findByBusinessId(b.id())) {
            if (m.getWindowStart() != null && (windowStart == null || m.getWindowStart().isBefore(windowStart))) {
                windowStart = m.getWindowStart();
            }
        }
        Marketer m = marketers.save(Marketer.builder().businessId(b.id()).profileId(p.getId()).userId(p.getUserId() == null ? p.getId() : p.getUserId())
                .fullName(p.getFullName()).sharePct(p.getSharePct()).months(p.getMonths()).whtPct(fees.whtPct())
                .status("ACTIVE").startedOn(start).windowStart(windowStart).windowEnd(windowStart == null ? null : windowStart.plusMonths(p.getMonths()))
                .build());
        log.info("Marketer agreement opened for {} on business {} from {}", p.getFullName(), b.name(), start);
        return m;
    }

    @Transactional
    public List<Referral> myReferrals() {
        return asSystem(() -> referrals.findByProfileIdOrderBySentAtDesc(myProfile().getId()));
    }

    /** My referrals, one page (newest first), with counts per stage. */
    @Transactional
    public PageWithCounts<Referral> myReferrals(String stage, Integer page, Integer size) {
        Paging.of(page, size, Sort.unsorted());
        return asSystem(() -> referrals(myProfile().getId(), stage, page, size));
    }

    /** My commissions, one page (latest month first), for a month and status, with counts and totals. */
    @Transactional
    public PageWithCounts<CommissionRow> myCommissions(String month, String status, Integer page, Integer size) {
        Paging.of(page, size, Sort.unsorted());
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            Page<Commission> result = commissions.findAll(commissionSpec(p.getId(), status, month), Paging.of(page, size,
                    Sort.by(Sort.Direction.DESC, "month").and(Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by("id"))));
            return PageWithCounts.of(result, c -> row(c, p.getFullName()), commissionCounts(p.getId(), month),
                    commissionTotals(p.getId(), status, month));
        });
    }

    @Transactional
    public Referral createReferral(PlatformRequests.ReferralRequest request) {
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            return referrals.save(Referral.builder().profileId(p.getId()).hotelName(request.hotelName().trim()).ownerName(request.ownerName())
                    .phone(request.phone()).email(request.email() == null ? null : request.email().trim().toLowerCase(Locale.ROOT))
                    .city(request.city()).note(request.note()).stage("LEAD").sentAt(Instant.now()).build());
        });
    }

    /** The businesses this marketer brought in, from tenant (GET /internal/businesses?marketerId=), with the agreement's share. */
    @Transactional
    public List<MarketerBusiness> myBusinesses() {
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            Map<String, Marketer> agreements = new HashMap<>();
            marketers.findByProfileIdOrderByCreatedAtDesc(p.getId()).forEach(m -> agreements.put(m.getBusinessId(), m));
            List<TenantClient.Business> businesses = new ArrayList<>(tenant.businesses(null, p.getId()));
            if (businesses.isEmpty()) {
                for (String id : agreements.keySet()) {
                    businessNames.business(id).ifPresent(businesses::add);
                }
            }
            List<MarketerBusiness> out = new ArrayList<>();
            for (TenantClient.Business b : businesses) {
                Marketer m = agreements.get(b.id());
                Commission last = commissions.findByProfileIdOrderByMonthDesc(p.getId()).stream().filter(c -> b.id().equals(c.getBusinessId())).findFirst().orElse(null);
                BigDecimal earned = Money.of(commissions.findByProfileIdOrderByMonthDesc(p.getId()).stream().filter(c -> b.id().equals(c.getBusinessId()))
                        .map(Commission::getNet).reduce(BigDecimal.ZERO, BigDecimal::add));
                out.add(new MarketerBusiness(b.id(), b.name(), b.city(), b.stage(), b.hotelCount(), b.createdAt(),
                        m == null ? p.getSharePct() : m.getSharePct(), last == null ? null : last.getNet(), last == null ? null : last.getMonth(),
                        m == null ? null : m.getStatus(), m == null ? null : m.getStartedOn(), m == null ? null : m.getEndedOn(),
                        m == null ? null : m.getWindowStart(), m == null ? null : m.getWindowEnd(), m == null ? p.getMonths() : m.getMonths(), earned));
            }
            return out;
        });
    }

    @Transactional
    public List<CommissionRow> myCommissions(String month) {
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            List<Commission> cs = StringUtils.hasText(month) ? commissions.findByProfileIdAndMonthOrderByCreatedAtAsc(p.getId(), month)
                    : commissions.findByProfileIdOrderByMonthDesc(p.getId());
            return cs.stream().map(c -> row(c, p.getFullName())).toList();
        });
    }

    /** A plain-text statement of one month's commissions. */
    @Transactional
    public CommissionStatement myStatement(String month) {
        return asSystem(() -> {
            MarketerProfile p = myProfile();
            String m = StringUtils.hasText(month) ? month : YearMonth.now(ZoneId.of("Africa/Lagos")).toString();
            List<Commission> cs = commissions.findByProfileIdAndMonthOrderByCreatedAtAsc(p.getId(), m);
            NumberFormat naira = NumberFormat.getNumberInstance(Locale.UK);
            naira.setMinimumFractionDigits(2);
            naira.setMaximumFractionDigits(2);
            StringBuilder t = new StringBuilder();
            t.append("Hospio360 commission statement\n").append("Marketer: ").append(p.getFullName()).append('\n')
                    .append("Month: ").append(YearMonth.parse(m).format(DateTimeFormatter.ofPattern("MMMM yyyy"))).append("\n\n");
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal wht = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            for (Commission c : cs) {
                String business = businessNames.name(c.getBusinessId());
                t.append("  ").append(business == null ? c.getBusinessId() : business).append(": fee NGN ").append(naira.format(c.getBaseAmount()))
                        .append(" → share NGN ").append(naira.format(c.getGross())).append(", WHT NGN ").append(naira.format(c.getWht()))
                        .append(", net NGN ").append(naira.format(c.getNet())).append(" (").append(c.getStatus().toLowerCase(Locale.ROOT)).append(")\n");
                gross = gross.add(c.getGross());
                wht = wht.add(c.getWht());
                net = net.add(c.getNet());
            }
            if (cs.isEmpty()) {
                t.append("  No commissions this month.\n");
            }
            t.append("\nGross: NGN ").append(naira.format(gross)).append("\nWHT: NGN ").append(naira.format(wht)).append("\nNet: NGN ").append(naira.format(net)).append('\n');
            if (StringUtils.hasText(p.getBankAccount())) {
                t.append("\nPaid to: ").append(p.getBankName()).append(' ').append(p.getBankAccount()).append(" (").append(p.getBankAccountName()).append(")\n");
            }
            return new CommissionStatement(m, p.getFullName(), Money.of(gross), Money.of(wht), Money.of(net), t.toString());
        });
    }

    // ---- listeners -----------------------------------------------------------------------------------

    /** A business was created: the referral named on it (or the marketer's lead with the same hotel name) moves to ONBOARDING. */
    @Transactional
    public void onBusinessCreated(String businessId, String name, String marketerId, String referralId) {
        asSystem(() -> {
            if (!StringUtils.hasText(marketerId) && !StringUtils.hasText(referralId)) {
                return null;
            }
            Referral r = null;
            if (StringUtils.hasText(referralId)) {
                r = referrals.findById(referralId).orElse(null);
            }
            if (r == null && StringUtils.hasText(marketerId)) {
                r = referrals.findByProfileIdOrderBySentAtDesc(marketerId).stream()
                        .filter(x -> x.getBusinessId() == null && x.getHotelName() != null && name != null
                                && x.getHotelName().trim().equalsIgnoreCase(name.trim())).findFirst().orElse(null);
            }
            if (r != null) {
                r.setBusinessId(businessId);
                if (!"LIVE".equals(r.getStage())) {
                    r.setStage("ONBOARDING");
                }
                referrals.save(r);
            }
            return null;
        });
    }

    /** A business went LIVE: the agreement for its marketer opens (once) and the referral follows. */
    @Transactional
    public void onBusinessLive(String businessId) {
        asSystem(() -> {
            referrals.findByBusinessId(businessId).ifPresent(r -> {
                r.setStage("LIVE");
                referrals.save(r);
            });
            TenantClient.Business b = tenant.business(businessId).orElse(null);
            if (b == null || !StringUtils.hasText(b.marketerId())) {
                return null;
            }
            MarketerProfile p = profiles.findById(b.marketerId()).orElse(null);
            if (p == null) {
                log.warn("Business {} names marketer {} but no profile has that id; no agreement created", businessId, b.marketerId());
                return null;
            }
            LocalDate today = LocalDate.now(LAGOS);
            // Never two earning at once: an agreement of another marketer still open on this business ends today.
            for (Marketer m : marketers.findByBusinessId(businessId)) {
                if ("ACTIVE".equals(m.getStatus()) && !p.getId().equals(m.getProfileId())) {
                    m.setStatus("ENDED");
                    m.setEndedOn(today);
                    m.setEndedReason("The business names another marketer (" + p.getFullName() + ")");
                    marketers.save(m);
                }
            }
            // Once per business and marketer: back to LIVE from AT_RISK keeps the same agreement; an ENDED marketer opens none.
            if (marketers.findByBusinessIdAndProfileId(businessId, p.getId()).isEmpty() && !"ENDED".equals(p.getStatus())) {
                openAgreement(b, p, today);
            }
            return null;
        });
    }

    /** A marketer signed in for the first time: PENDING → ACTIVE. */
    @Transactional
    public void onUserSignedIn(String userId) {
        if (userId == null) {
            return;
        }
        asSystem(() -> {
            profiles.findByUserId(userId).filter(p -> "PENDING".equals(p.getStatus())).ifPresent(p -> {
                p.setStatus("ACTIVE");
                p.setJoinedAt(Instant.now());
                profiles.save(p);
            });
            return null;
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** The caller's profile: by user id, else by email (a profile seeded before its login existed is bound now). */
    MarketerProfile myProfile() {
        HospioUserDetails user = CurrentUser.require();
        MarketerProfile p = profiles.findByUserId(user.getUserId()).orElse(null);
        if (p == null && StringUtils.hasText(user.getEmail())) {
            p = profiles.findByEmailIgnoreCase(user.getEmail()).orElse(null);
            if (p != null) {
                p.setUserId(user.getUserId());
                if ("PENDING".equals(p.getStatus())) {
                    p.setStatus("ACTIVE");
                    p.setJoinedAt(Instant.now());
                }
                profiles.save(p);
            }
        }
        if (p == null) {
            throw new ResourceNotFoundException("No marketer profile is linked to this login");
        }
        return p;
    }

    private static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("marketers"), work);
    }

    private MarketerRow row(MarketerProfile p) {
        List<Commission> cs = commissions.findByProfileIdOrderByMonthDesc(p.getId());
        Money3 m = money(p.getId(), cs);
        Map<String, Integer> byStage = businessesByStage(p.getId());
        return new MarketerRow(p, byStage.values().stream().mapToInt(Integer::intValue).sum(), m.earned(), m.due(), byStage,
                agreementVersion().equals(p.getAgreementVersion()), p.getBankCheckedAt() != null);
    }

    /**
     * earned: every line's net (old monthly rows too); due: statements made and not paid yet (draft, queried, approved); paid: what was
     * paid; open: lines waiting for the next statement; carried: statements under the minimum waiting to be carried into the next.
     */
    record Money3(BigDecimal earned, BigDecimal due, BigDecimal paid, BigDecimal open, BigDecimal carried) {
    }

    Money3 money(String profileId, List<Commission> cs) {
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal open = BigDecimal.ZERO;
        BigDecimal legacyDue = BigDecimal.ZERO;
        BigDecimal legacyPaid = BigDecimal.ZERO;
        for (Commission c : cs) {
            earned = earned.add(c.getNet());
            if (c.getSource() == null) {
                if ("PAID".equals(c.getStatus())) {
                    legacyPaid = legacyPaid.add(c.getNet());
                } else if ("DUE".equals(c.getStatus())) {
                    legacyDue = legacyDue.add(c.getNet());
                }
            } else if (c.getStatementId() == null) {
                open = open.add(c.getNet());
            }
        }
        BigDecimal due = legacyDue;
        BigDecimal paid = legacyPaid;
        BigDecimal carried = BigDecimal.ZERO;
        for (com.hospio.platform.entity.MarketerStatement s : statements.findByProfileIdOrderByMonthDesc(profileId)) {
            switch (s.getStatus()) {
                case "DRAFT", "QUERIED", "APPROVED" -> due = due.add(s.getPayable());
                case "PAID" -> paid = paid.add(s.getPaidAmount() == null ? s.getPayable() : s.getPaidAmount());
                case "CARRIED_OVER" -> carried = s.getCarriedToId() == null ? carried.add(s.getPayable()) : carried;
                default -> { }
            }
        }
        return new Money3(Money.of(earned), Money.of(due), Money.of(paid), Money.of(open), Money.of(carried));
    }

    /** Every business that names this marketer, whatever its stage (tenant), by stage. */
    Map<String, Integer> businessesByStage(String profileId) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (TenantClient.Business b : tenant.businesses(null, profileId)) {
            out.merge(b.stage() == null ? "UNKNOWN" : b.stage(), 1, Integer::sum);
        }
        return out;
    }

    int referred(String profileId) {
        return tenant.businesses(null, profileId).size();
    }

    private CommissionRow row(Commission c, String marketerName) {
        return new CommissionRow(c, marketerName, businessNames.name(c.getBusinessId()));
    }

    private String marketerName(Commission c, Map<String, String> cache) {
        String key = c.getProfileId() != null ? "p:" + c.getProfileId() : "m:" + c.getMarketerId();
        return cache.computeIfAbsent(key, k -> c.getProfileId() != null
                ? profiles.findById(c.getProfileId()).map(MarketerProfile::getFullName).orElse(null)
                : marketers.findById(c.getMarketerId()).map(Marketer::getFullName).orElse(null));
    }

    static BigDecimal sum(List<Commission> cs, String status) {
        return Money.of(cs.stream().filter(c -> status == null || status.equals(c.getStatus())).map(Commission::getNet).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private MarketerProfile findProfile(String id) {
        return profiles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Marketer not found"));
    }

    private String newReferralCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("HM-");
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            if (profiles.findByReferralCode(sb.toString()).isEmpty()) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Could not find a free referral code");
    }

    private static boolean contains(String value, String like) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(like);
    }
}
