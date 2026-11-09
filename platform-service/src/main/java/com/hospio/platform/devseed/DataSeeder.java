package com.hospio.platform.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.FeePayment;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.Referral;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.repository.FeePaymentRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.ReferralRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import com.hospio.platform.service.FeeService;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.FeeSettings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staging data for the admin portal, created when missing and hospio.seed.enabled=true: the platform settings
 * defaults; for Royal Suites a PAID statement for last month and a DUE one for this month, three open tickets;
 * the marketer profile for Ese Okoro (the seeded MARKETER login) with two referrals.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    public static final String MARKETER_PROFILE_ID = "60000000-0000-4000-8000-000000000001";
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final PlatformSettingsService settings;
    private final FeeStatementRepository statements;
    private final FeePaymentRepository payments;
    private final SupportTicketRepository tickets;
    private final MarketerProfileRepository profiles;
    private final ReferralRepository referrals;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWith(Tenant.system("seed"), () -> tx.executeWithoutResult(status -> {
            int created = settings.seedDefaults();
            FeeSettings fees = settings.fees();
            seedStatements(fees);
            seedTickets();
            seedMarketer(fees);
            log.info("Admin portal seed done ({} settings written; statements, tickets and marketer created where missing)", created);
        }));
    }

    private void seedStatements(FeeSettings fees) {
        YearMonth thisMonth = YearMonth.now(LAGOS);
        YearMonth lastMonth = thisMonth.minusMonths(1);
        String business = SeedFixtures.BUSINESS_ID;
        if (statements.findByBusinessIdAndMonth(business, lastMonth.toString()).isEmpty()) {
            BigDecimal base = new BigDecimal("18450000.00");
            BigDecimal fee = FeeService.computeFee(FeeService.PERCENT_OF_PAYMENTS, new BigDecimal("2.50"), base, 0);
            FeeStatement s = statements.save(FeeStatement.builder().businessId(business).businessName(SeedFixtures.BUSINESS_NAME).month(lastMonth.toString())
                    .baseAmount(base).feeAmount(fee).paidAmount(fee).status("PAID").dueOn(FeeService.dueOn(lastMonth, fees.dueDay()))
                    .lines("[{\"hotelId\":\"" + SeedFixtures.HOTEL_ID + "\",\"hotelName\":\"" + SeedFixtures.HOTEL_NAME + "\",\"base\":" + base + ",\"fee\":" + fee + "}]")
                    .sentAt(lastMonth.plusMonths(1).atDay(1).atStartOfDay(LAGOS).toInstant()).build());
            payments.save(FeePayment.builder().businessId(business).statementId(s.getId()).amount(fee).paidOn(FeeService.dueOn(lastMonth, fees.dueDay()).minusDays(2))
                    .reference("TRF-" + lastMonth.getYear() + lastMonth.getMonthValue() + "-ROYAL").recordedBy(SeedFixtures.ADMINS.get(2).userId())
                    .recordedByName(SeedFixtures.ADMINS.get(2).fullName()).build());
        }
        if (statements.findByBusinessIdAndMonth(business, thisMonth.toString()).isEmpty()) {
            BigDecimal base = new BigDecimal("6120000.00");
            BigDecimal fee = FeeService.computeFee(FeeService.PERCENT_OF_PAYMENTS, new BigDecimal("2.50"), base, 0);
            statements.save(FeeStatement.builder().businessId(business).businessName(SeedFixtures.BUSINESS_NAME).month(thisMonth.toString())
                    .baseAmount(base).feeAmount(fee).status("DUE").dueOn(FeeService.dueOn(thisMonth, fees.dueDay()))
                    .lines("[{\"hotelId\":\"" + SeedFixtures.HOTEL_ID + "\",\"hotelName\":\"" + SeedFixtures.HOTEL_NAME + "\",\"base\":" + base + ",\"fee\":" + fee + "}]")
                    .build());
        }
    }

    private void seedTickets() {
        String business = SeedFixtures.BUSINESS_ID;
        if (!tickets.findByBusinessIdAndStatusInOrderByCreatedAtDesc(business, List.of("OPEN", "IN_PROGRESS")).isEmpty()) {
            return;
        }
        SeedFixtures.Person manager = SeedFixtures.MANAGER;
        SeedFixtures.Person owner = SeedFixtures.OWNER;
        SeedFixtures.Person kola = SeedFixtures.ADMINS.get(1);
        Instant now = Instant.now();
        tickets.save(SupportTicket.builder().businessId(business).hotelId(SeedFixtures.HOTEL_ID).businessName(SeedFixtures.BUSINESS_NAME)
                .subject("Front desk POS keeps going offline").body("The front desk terminal drops every evening around 8pm; guests end up paying cash.")
                .priority("HIGH").status("IN_PROGRESS").openedBy(manager.userId()).openedByName(manager.fullName())
                .assignedTo(kola.userId()).assignedToName(kola.fullName()).lastActivityAt(now.minus(3, ChronoUnit.HOURS)).build());
        tickets.save(SupportTicket.builder().businessId(business).hotelId(SeedFixtures.HOTEL_ID).businessName(SeedFixtures.BUSINESS_NAME)
                .subject("Add a second laundry user").body("We hired another laundry attendant; please add her to the Laundry role.")
                .priority("NORMAL").status("OPEN").openedBy(manager.userId()).openedByName(manager.fullName()).lastActivityAt(now.minus(1, ChronoUnit.DAYS)).build());
        tickets.save(SupportTicket.builder().businessId(business).businessName(SeedFixtures.BUSINESS_NAME)
                .subject("Fee statement does not match our payments").body("Last month's statement shows a higher base than our payments report.")
                .priority("NORMAL").status("OPEN").openedBy(owner.userId()).openedByName(owner.fullName()).lastActivityAt(now.minus(2, ChronoUnit.DAYS)).build());
    }

    private void seedMarketer(FeeSettings fees) {
        SeedFixtures.Person ese = SeedFixtures.ADMINS.stream().filter(p -> "MARKETER".equals(p.role())).findFirst().orElse(null);
        if (ese == null) {
            return;
        }
        MarketerProfile p = profiles.findById(MARKETER_PROFILE_ID).or(() -> profiles.findByEmailIgnoreCase(ese.email())).orElse(null);
        if (p == null) {
            p = MarketerProfile.builder().userId(ese.userId()).fullName(ese.fullName()).email(ese.email()).phone(ese.phone()).status("ACTIVE")
                    .sharePct(fees.marketerSharePct()).months(fees.marketerMonths()).joinedAt(Instant.now().minus(60, ChronoUnit.DAYS))
                    .referralCode("HM-ESE001").bankName("GTBank").bankAccount("0123456789").bankAccountName("Ese Okoro").build();
            p.setId(MARKETER_PROFILE_ID);
            profiles.save(p);
        }
        if (referrals.countByProfileId(p.getId()) == 0) {
            referrals.save(Referral.builder().profileId(p.getId()).hotelName("Blue Lagoon Suites").ownerName("Chika Umeh").phone("+2348030000101")
                    .email("chika@bluelagoon.ng").city("Lagos").stage("CONTACTED").note("Met at the Lagos hoteliers' breakfast; wants a demo.")
                    .sentAt(Instant.now().minus(12, ChronoUnit.DAYS)).build());
            referrals.save(Referral.builder().profileId(p.getId()).hotelName("Savannah Lodge Abuja").ownerName("Hauwa Bello").phone("+2348030000102")
                    .email("hauwa@savannahlodge.ng").city("Abuja").stage("LEAD").note("Three-branch lodge, currently on paper.")
                    .sentAt(Instant.now().minus(4, ChronoUnit.DAYS)).build());
        }
    }
}
