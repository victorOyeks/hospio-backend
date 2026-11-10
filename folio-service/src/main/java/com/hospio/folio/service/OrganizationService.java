package com.hospio.folio.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.InvoiceIssuedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.folio.config.ApprovalLimits;
import com.hospio.folio.dto.InvoiceRequest;
import com.hospio.folio.dto.MemberRequest;
import com.hospio.folio.dto.OrgPaymentRequest;
import com.hospio.folio.dto.OrganizationRequest;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.Invoice;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.entity.OrganizationMember;
import com.hospio.folio.entity.OrganizationPayment;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.InvoiceRepository;
import com.hospio.folio.repository.OrganizationMemberRepository;
import com.hospio.folio.repository.OrganizationPaymentRepository;
import com.hospio.folio.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manager · Organizations and Finance 5.x: agreements, staff lists, statements, invoices and payments. */
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final FolioRepository folios;
    private final InvoiceRepository invoices;
    private final OrganizationPaymentRepository payments;
    private final ApprovalLimits limits;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<Organization> list() {
        return organizations.findByHotelIdOrderByName(TenantContext.requireHotel());
    }

    @Transactional(readOnly = true)
    public Organization get(String id) {
        return organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    @Transactional
    public Organization save(String id, OrganizationRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Organization org = id == null ? new Organization() : get(id);
        BigDecimal current = Money.of(org.getCreditLimit() == null ? BigDecimal.ZERO : org.getCreditLimit());
        BigDecimal credit = Money.of(request.creditLimit() == null ? current : request.creditLimit());
        if (credit.signum() < 0) {
            throw new BadRequestException("A credit limit can't be negative");
        }
        boolean ownerSets = user.has(com.hospio.common.enums.Role.OWNER) || user.isPlatform();
        BigDecimal toOwner = null;
        if (credit.compareTo(current) != 0 && credit.compareTo(limits.getOrgCreditOwnerLimit()) > 0 && !ownerSets) {
            // Above the owner's threshold: everything else is saved now, the limit waits for the owner.
            if (org.getCreditApprovalId() != null && org.getRequestedCreditLimit() != null) {
                if (org.getRequestedCreditLimit().compareTo(credit) != 0) {
                    throw new BadRequestException("A credit limit of " + naira(org.getRequestedCreditLimit()) + " for " + org.getName()
                            + " is already waiting for the owner. Wait for their answer first");
                }
            } else {
                toOwner = credit;
            }
            credit = current;
        } else if (ownerSets && org.getCreditApprovalId() != null && credit.compareTo(current) != 0) {
            // The owner set the limit themselves: the waiting request is answered by that.
            clearRequest(org);
        }
        org.setName(request.name());
        org.setContactName(request.contactName());
        org.setContactPhone(request.contactPhone());
        org.setContactEmail(request.contactEmail());
        org.setAddress(request.address());
        org.setCreditLimit(credit);
        org.setDailyCap(request.dailyCap());
        if (request.billingCycle() != null) {
            org.setBillingCycle(request.billingCycle());
        }
        if (request.paymentTermsDays() != null) {
            org.setPaymentTermsDays(request.paymentTermsDays());
        }
        org.setAgreementNotes(request.agreementNotes());
        Organization saved = organizations.save(org);
        if (toOwner != null) {
            String approvalId = UUID.randomUUID().toString();
            saved.setRequestedCreditLimit(toOwner);
            saved.setCreditApprovalId(approvalId);
            saved.setCreditRequestedByName(user.getFullName());
            saved.setCreditRequestedAt(java.time.Instant.now());
            saved = organizations.save(saved);
            events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder()
                    .approvalId(approvalId).sourceModule("FOLIO").sourceRef(saved.getId()).kind(CREDIT_LIMIT).amount(toOwner)
                    .requesterId(user.getUserId()).requesterName(user.getFullName()).approverLevel("OWNER")
                    .description(saved.getName() + " · credit limit " + naira(saved.getCreditLimit()) + " → " + naira(toOwner)).build());
            saved.setCreditSentToOwner(true);
        }
        return saved;
    }

    public static final String CREDIT_LIMIT = "CREDIT_LIMIT";

    /**
     * The owner's answer to a credit limit request (APPROVAL_DECIDED from platform-service): approved, the requested
     * limit becomes the limit; declined, the old one stays. A question leaves it waiting. Answers to a request that was
     * replaced or already settled are ignored.
     */
    @Transactional
    public void applyCreditDecision(String approvalId, String decision) {
        organizations.findByCreditApprovalId(approvalId).ifPresent(org -> {
            if ("APPROVED".equals(decision)) {
                org.setCreditLimit(Money.of(org.getRequestedCreditLimit()));
                clearRequest(org);
            } else if ("DECLINED".equals(decision)) {
                clearRequest(org);
            } else {
                return;
            }
            organizations.save(org);
        });
    }

    private static void clearRequest(Organization org) {
        org.setRequestedCreditLimit(null);
        org.setCreditApprovalId(null);
        org.setCreditRequestedByName(null);
        org.setCreditRequestedAt(null);
    }

    static String naira(BigDecimal amount) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(java.util.Locale.ENGLISH);
        f.setMaximumFractionDigits(amount != null && amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return "₦" + f.format(amount == null ? BigDecimal.ZERO : amount);
    }

    @Transactional(readOnly = true)
    public List<OrganizationMember> members(String orgId) {
        return members.findByOrganizationIdOrderByFullName(get(orgId).getId());
    }

    @Transactional
    public OrganizationMember addMember(String orgId, MemberRequest request) {
        get(orgId);
        return members.save(OrganizationMember.builder().organizationId(orgId).fullName(request.fullName()).staffRef(request.staffRef())
                .phone(request.phone()).email(request.email()).build());
    }

    /** Put on hold (no new stays on account) or back to active. */
    @Transactional
    public Organization setStatus(String id, String status) {
        if (!"ACTIVE".equals(status) && !"ON_HOLD".equals(status)) {
            throw new BadRequestException("Status is ACTIVE or ON_HOLD");
        }
        Organization org = get(id);
        org.setStatus(status);
        return organizations.save(org);
    }

    /** Remove a staff member from the account (they can't stay on it any more) or allow them again. */
    @Transactional
    public OrganizationMember setMemberActive(String orgId, String memberId, boolean active) {
        OrganizationMember m = members.findById(memberId).filter(x -> x.getOrganizationId().equals(get(orgId).getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Staff member not found on this account"));
        m.setActive(active);
        return members.save(m);
    }

    @Transactional
    public List<OrganizationMember> importMembers(String orgId, List<MemberRequest> rows) {
        return rows.stream().map(r -> addMember(orgId, r)).toList();
    }

    /** Stays & bills: the organization's folios and what is still owed. */
    @Transactional(readOnly = true)
    public List<Folio> stays(String orgId) {
        return folios.findByOrganizationIdOrderByOpenedAtDesc(get(orgId).getId());
    }

    @Transactional(readOnly = true)
    public BigDecimal outstanding(String orgId) {
        return Money.of(stays(orgId).stream().map(Folio::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Transactional
    public Invoice createInvoice(String orgId, InvoiceRequest request) {
        Organization org = get(orgId);
        BigDecimal amount = stays(orgId).stream()
                .filter(f -> !f.getOpenedAt().isBefore(request.periodFrom().atStartOfDay().toInstant(ZoneOffset.UTC))
                        && f.getOpenedAt().isBefore(request.periodTo().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
                .map(Folio::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.signum() <= 0) {
            throw new BadRequestException("Nothing to invoice for that period");
        }
        String number = "INV-" + LocalDate.now().getYear() + "-" + String.format("%05d", invoices.countByHotelId(TenantContext.requireHotel()) + 1);
        Invoice invoice = invoices.save(Invoice.builder().organizationId(orgId).number(number).periodFrom(request.periodFrom())
                .periodTo(request.periodTo()).amount(Money.of(amount))
                .dueDate(request.dueDate() == null ? LocalDate.now().plusDays(org.getPaymentTermsDays()) : request.dueDate())
                .status(Invoice.Status.SENT).sentAt(Instant.now()).build());
        events.publish(KafkaTopics.INVOICE_ISSUED, InvoiceIssuedEvent.builder().invoiceId(invoice.getId()).organizationId(orgId)
                .organizationName(org.getName()).amount(invoice.getAmount()).dueDate(invoice.getDueDate()).build());
        return invoice;
    }

    @Transactional(readOnly = true)
    public List<Invoice> invoices(String orgId) {
        return invoices.findByOrganizationIdOrderByDueDateDesc(get(orgId).getId());
    }

    @Transactional
    public OrganizationPayment recordPayment(String orgId, OrgPaymentRequest request) {
        get(orgId);
        OrganizationPayment payment = payments.save(OrganizationPayment.builder().organizationId(orgId).invoiceId(request.invoiceId())
                .amount(Money.of(request.amount())).method(request.method()).reference(request.reference())
                .recordedBy(CurrentUser.require().getUserId()).build());
        if (request.invoiceId() != null) {
            Invoice invoice = invoices.findById(request.invoiceId()).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
            invoice.setPaidAmount(Money.add(invoice.getPaidAmount(), request.amount()));
            invoice.setStatus(invoice.getPaidAmount().compareTo(invoice.getAmount()) >= 0 ? Invoice.Status.PAID : Invoice.Status.PART_PAID);
            invoices.save(invoice);
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public List<OrganizationPayment> payments(String orgId) {
        return payments.findByOrganizationIdOrderByReceivedAtDesc(get(orgId).getId());
    }
}
