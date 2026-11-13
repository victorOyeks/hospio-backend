package com.hospio.folio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.folio.dto.OrganizationViews.InvoiceView;
import com.hospio.folio.dto.OrganizationViews.OrganizationSummary;
import com.hospio.folio.dto.OrganizationViews.OrganizationsOverview;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.Invoice;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.entity.OrganizationMember;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.InvoiceRepository;
import com.hospio.folio.repository.OrganizationMemberRepository;
import com.hospio.folio.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Organizations paged in the database against a real Postgres built by the service's Liquibase: pages without gaps or overlap,
 * counts and totals over the whole filtered range on every page, search and status on the server, figures per account, invoices by
 * effective status and stays by day. Needs Docker; skipped where there is none.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "hospio.worker.enabled=false",
        "services.internal.api-key=test-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class OrganizationPagingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("folio-schema.sql");

    static final String BIZ = "b1";
    static final String HOTEL = "h1";
    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired OrganizationQueryService query;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMemberRepository members;
    @Autowired FolioRepository folios;
    @Autowired InvoiceRepository invoices;
    @Autowired OrganizationService organizationService;
    @Autowired FolioService folioService;
    @Autowired com.hospio.folio.repository.FolioLineRepository folioLines;

    String acme;

    @BeforeEach
    void seed() {
        TenantContext.set(new Tenant(BIZ, HOTEL, "u1", "Tester", Set.of(), "hotel"));
        invoices.deleteAll();
        folios.deleteAll();
        members.deleteAll();
        organizations.deleteAll();
        // 48 accounts at this hotel: QA PG 01..48, every fourth on hold; one at another hotel that must never show.
        for (int i = 1; i <= 48; i++) {
            Organization o = organizations.save(Organization.builder().businessId(BIZ).hotelId(HOTEL).name(String.format("QA PG %02d", i))
                    .contactName(i == 7 ? "Zainab Musa" : "Contact " + i).status(i % 4 == 0 ? "ON_HOLD" : "ACTIVE").build());
            if (i == 1) {
                acme = o.getId();
            }
        }
        organizations.save(Organization.builder().businessId(BIZ).hotelId("h2").name("QA PG elsewhere").build());
        LocalDate today = LocalDate.now(LAGOS);
        // Account 01: 3 active staff and 1 removed, 30 stays (2 open), 3 invoices (1 overdue, 1 paid, 1 sent not due).
        for (int i = 0; i < 4; i++) {
            members.save(OrganizationMember.builder().businessId(BIZ).hotelId(HOTEL).organizationId(acme).fullName("Staff " + i).active(i < 3).build());
        }
        for (int i = 0; i < 30; i++) {
            Instant opened = today.minusDays(i * 3L).atTime(10, 0).atZone(LAGOS).toInstant();
            folios.save(Folio.builder().businessId(BIZ).hotelId(HOTEL).organizationId(acme).guestName("Guest " + i).roomNumber("1" + i)
                    .status(i < 2 ? Folio.Status.OPEN : Folio.Status.CLOSED).balance(new BigDecimal("1000.00"))
                    .totalCharges(new BigDecimal("5000.00")).openedAt(opened).build());
        }
        invoice("INV-1", acme, "50000", "0", today.minusDays(5), Invoice.Status.SENT);
        invoice("INV-2", acme, "20000", "20000", today.minusDays(40), Invoice.Status.PAID);
        invoice("INV-3", acme, "10000", "4000", today.plusDays(10), Invoice.Status.PART_PAID);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void invoice(String number, String org, String amount, String paid, LocalDate due, Invoice.Status status) {
        invoices.save(Invoice.builder().businessId(BIZ).hotelId(HOTEL).organizationId(org).number(number).periodFrom(due.minusDays(60))
                .periodTo(due.minusDays(30)).amount(new BigDecimal(amount)).paidAmount(new BigDecimal(paid)).dueDate(due).status(status).build());
    }

    @Test
    void postingsLockTheFolioAndTotalItInTheDatabase() {
        Folio f = folios.save(Folio.builder().businessId(BIZ).hotelId(HOTEL).guestName("Lock Test").openedAt(Instant.now()).build());
        folioService.postCharge(f, "BAR", "ORDER-it-1", "Bar", new BigDecimal("13000"), new BigDecimal("650"), "BAR", null, "Segun");
        folioService.postCharge(f, "BAR", "ORDER-it-2", "Bar", new BigDecimal("2000"), BigDecimal.ZERO, "BAR", null, "Segun");
        folioService.recordPayment(f, "pay-it-1", new BigDecimal("5000"), "CARD", "Victor");

        Folio saved = folios.findById(f.getId()).orElseThrow();
        assertThat(saved.getTotalCharges()).isEqualByComparingTo("15000");
        assertThat(saved.getTotalTax()).isEqualByComparingTo("650");
        assertThat(saved.getTotalPayments()).isEqualByComparingTo("5000");
        assertThat(saved.getBalance()).isEqualByComparingTo("10650");
        assertThat(saved.getVersion()).isGreaterThan(0L);
        folioLines.deleteAll(folioLines.findByFolioIdOrderByPostedAt(f.getId()));
    }

    @Test
    void invoiceNumbersFollowTheHotelsHighestThisYearAndRepeatOnlyAcrossHotels() {
        String prefix = "INV-" + LocalDate.now().getYear() + "-";
        invoice(prefix + "00007", acme, "1000", "0", LocalDate.now(LAGOS).plusDays(5), Invoice.Status.SENT);

        assertThat(organizationService.nextInvoiceNumber(HOTEL)).isEqualTo(prefix + "00008");
        assertThat(organizationService.nextInvoiceNumber("h2")).isEqualTo(prefix + "00001");
        // The same number at another hotel is fine; twice at one hotel is not.
        invoices.save(Invoice.builder().businessId(BIZ).hotelId("h2").organizationId(acme).number(prefix + "00007").periodFrom(LocalDate.now())
                .periodTo(LocalDate.now()).amount(BigDecimal.TEN).paidAmount(BigDecimal.ZERO).dueDate(LocalDate.now()).status(Invoice.Status.SENT).build());
        assertThatThrownBy(() -> invoice(prefix + "00007", acme, "1000", "0", LocalDate.now(LAGOS), Invoice.Status.SENT))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void accountsPageWithoutGapsAndCountsOnEveryPage() {
        PageWithCounts<OrganizationSummary> p0 = query.page(null, null, 0, 25, null);
        PageWithCounts<OrganizationSummary> p1 = query.page(null, null, 1, 25, null);
        assertThat(p0.totalElements()).isEqualTo(48);
        assertThat(p0.content()).hasSize(25);
        assertThat(p1.content()).hasSize(23);
        assertThat(p1.content().get(0).organization().getName()).isEqualTo("QA PG 26");
        Set<String> ids = new HashSet<>();
        p0.content().forEach(o -> ids.add(o.organization().getId()));
        p1.content().forEach(o -> ids.add(o.organization().getId()));
        assertThat(ids).hasSize(48);
        assertThat(p0.counts()).isEqualTo(p1.counts());
        assertThat(p0.counts()).containsEntry("all", 48L).containsEntry("ON_HOLD", 12L).containsEntry("ACTIVE", 36L);
        assertThat(p0.totals().get("outstanding")).isEqualByComparingTo("30000");
        assertThat(p1.totals()).isEqualTo(p0.totals());
    }

    @Test
    void searchAndStatusRunOnTheServer() {
        PageWithCounts<OrganizationSummary> zainab = query.page("zainab", null, 0, 25, null);
        assertThat(zainab.content()).extracting(o -> o.organization().getName()).containsExactly("QA PG 07");
        PageWithCounts<OrganizationSummary> onHold = query.page(null, "ON_HOLD", 0, 25, null);
        assertThat(onHold.totalElements()).isEqualTo(12);
        assertThat(onHold.counts().get("all")).isEqualTo(48);
        assertThat(query.page("pg 4", null, 0, 25, "name,desc").content()).extracting(o -> o.organization().getName())
                .containsExactly("QA PG 48", "QA PG 47", "QA PG 46", "QA PG 45", "QA PG 44", "QA PG 43", "QA PG 42", "QA PG 41", "QA PG 40");
        assertThatThrownBy(() -> query.page(null, null, 0, 101, null)).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> query.page(null, null, 0, 25, "password")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void figuresComeWithEachAccount() {
        OrganizationSummary a = query.one(acme);
        assertThat(a.outstanding()).isEqualByComparingTo("30000");
        assertThat(a.staffCount()).isEqualTo(3);
        assertThat(a.stays()).isEqualTo(30);
        assertThat(a.openStays()).isEqualTo(2);
        assertThat(a.runningBalance()).isEqualByComparingTo("2000");
        assertThat(a.invoices()).isEqualTo(3);
        assertThat(a.openInvoices()).isEqualTo(2);
        assertThat(a.invoicesLeft()).isEqualByComparingTo("56000");
        assertThat(a.overdueInvoices()).isEqualTo(1);
        assertThat(a.overdueAmount()).isEqualByComparingTo("50000");
        assertThat(a.overdueNumber()).isEqualTo("INV-1");
        assertThat(a.staysThisMonth()).isEqualTo(30 - query.stays(acme, null, null, null, LocalDate.now(LAGOS).withDayOfMonth(1).minusDays(1), 0, 100, null).totalElements());
        OrganizationsOverview o = query.overview();
        assertThat(o.accounts()).isEqualTo(48);
        assertThat(o.staffCanBook()).isEqualTo(3);
        assertThat(o.onHold()).isEqualTo(12);
        assertThat(o.overdueOrganizations()).containsExactly("QA PG 01");
        assertThat(o.overdueAmount()).isEqualByComparingTo("50000");
        assertThat(o.openInvoices()).isEqualTo(2);
    }

    @Test
    void invoicesByEffectiveStatusWithTotals() {
        PageWithCounts<InvoiceView> all = query.invoices(null, null, null, 0, 25, null);
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.content()).extracting(i -> i.invoice().getNumber()).containsExactly("INV-3", "INV-1", "INV-2");
        assertThat(all.content().get(1).overdue()).isTrue();
        assertThat(all.content().get(1).organizationName()).isEqualTo("QA PG 01");
        assertThat(all.counts()).containsEntry("OVERDUE", 1L).containsEntry("OPEN", 2L).containsEntry("PAID", 1L).containsEntry("SENT", 0L)
                .containsEntry("PART_PAID", 1L);
        assertThat(all.totals().get("left")).isEqualByComparingTo("56000");
        PageWithCounts<InvoiceView> overdue = query.invoices(acme, "overdue", null, 0, 25, null);
        assertThat(overdue.content()).extracting(i -> i.invoice().getNumber()).containsExactly("INV-1");
        assertThat(overdue.totals().get("left")).isEqualByComparingTo("50000");
        assertThat(query.invoices(null, null, "qa pg 01", 0, 25, null).totalElements()).isEqualTo(3);
        assertThat(query.invoices(null, null, "inv-2", 0, 25, null).totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> query.invoices(null, "LOST", null, 0, 25, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void staysPageByDayWithTotals() {
        PageWithCounts<Folio> p0 = query.stays(acme, null, null, null, null, 0, 25, null);
        PageWithCounts<Folio> p1 = query.stays(acme, null, null, null, null, 1, 25, null);
        assertThat(p0.totalElements()).isEqualTo(30);
        assertThat(p1.content()).hasSize(5);
        assertThat(p0.content().get(0).getGuestName()).isEqualTo("Guest 0");
        assertThat(p1.content().get(0).getGuestName()).isEqualTo("Guest 25");
        assertThat(p0.totals().get("balance")).isEqualByComparingTo("30000");
        assertThat(p0.counts()).containsEntry("OPEN", 2L).containsEntry("CLOSED", 28L);
        LocalDate today = LocalDate.now(LAGOS);
        PageWithCounts<Folio> week = query.stays(acme, null, null, today.minusDays(6), today, 0, 25, null);
        assertThat(week.content()).extracting(Folio::getGuestName).containsExactly("Guest 0", "Guest 1", "Guest 2");
        assertThat(week.totals().get("balance")).isEqualByComparingTo("3000");
        assertThat(query.stays(acme, "open", null, null, null, 0, 25, null).totalElements()).isEqualTo(2);
        assertThat(query.stays(acme, null, "guest 29", null, null, 0, 25, null).content()).hasSize(1);
        List<Folio> all = query.stays(acme, null, null, null, null, 0, 100, null).content();
        assertThat(all).hasSize(30);
    }
}
