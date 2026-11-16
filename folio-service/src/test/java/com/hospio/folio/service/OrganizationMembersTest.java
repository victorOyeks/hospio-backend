package com.hospio.folio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.folio.config.ApprovalLimits;
import com.hospio.folio.dto.MemberRequest;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.entity.OrganizationMember;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.InvoiceRepository;
import com.hospio.folio.repository.OrganizationMemberRepository;
import com.hospio.folio.repository.OrganizationPaymentRepository;
import com.hospio.folio.repository.OrganizationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DEF-073 / DEF-074 (Sam, 15 Nov): real details only, and one phone or email is one person on an account's list. */
class OrganizationMembersTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final OrganizationService service = new OrganizationService(organizations, members, mock(FolioRepository.class),
            mock(InvoiceRepository.class), mock(OrganizationPaymentRepository.class), mock(ApprovalLimits.class), mock(EventPublisher.class));

    @BeforeEach
    void org() {
        Organization o = new Organization();
        o.setId("o1");
        when(organizations.findById("o1")).thenReturn(Optional.of(o));
        when(members.save(any())).thenAnswer(i -> i.getArgument(0));
        when(members.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static OrganizationMember onList(String name, String phone, String email) {
        return OrganizationMember.builder().organizationId("o1").fullName(name).phone(phone).email(email).build();
    }

    @Test
    void realDetailsAreSavedTidied() {
        when(members.findByOrganizationIdOrderByFullName("o1")).thenReturn(List.of());
        OrganizationMember m = service.addMember("o1", new MemberRequest("  Ada   Okeke ", " JB-1 ", "0803 555 0192", " Ada@Company.NG "));
        assertThat(m.getFullName()).isEqualTo("Ada Okeke");
        assertThat(m.getEmail()).isEqualTo("ada@company.ng");
        assertThat(m.getStaffRef()).isEqualTo("JB-1");
    }

    @Test
    void invalidDetailsAreRefused() {
        when(members.findByOrganizationIdOrderByFullName("o1")).thenReturn(List.of());
        assertThatThrownBy(() -> service.addMember("o1", new MemberRequest("0000", null, "08035550192", null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Enter the person's full name");
        assertThatThrownBy(() -> service.addMember("o1", new MemberRequest("Ada Okeke", null, "0000", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Phone must be a real phone number");
        assertThatThrownBy(() -> service.addMember("o1", new MemberRequest("Ada Okeke", null, "0000 000 0000", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Phone must be a real phone number");
        assertThatThrownBy(() -> service.addMember("o1", new MemberRequest("Ada Okeke", null, "08035550192", "ada@")))
                .isInstanceOf(BadRequestException.class).hasMessage("\"ada@\" is not an email address");
        verify(members, never()).save(any());
    }

    @Test
    void onePhoneHoweverWrittenIsOnePerson() {
        when(members.findByOrganizationIdOrderByFullName("o1")).thenReturn(List.of(onList("Ada Okeke", "0803 555 0192", "ada@co.ng")));
        assertThatThrownBy(() -> service.addMember("o1", new MemberRequest("Bayo Ade", null, "+234 803 555 0192", null)))
                .isInstanceOf(BadRequestException.class).hasMessage("+234 803 555 0192 is already on this account for Ada Okeke");
        assertThatThrownBy(() -> service.addMember("o1", new MemberRequest("Bayo Ade", null, "08035550000", "ADA@co.ng")))
                .isInstanceOf(BadRequestException.class).hasMessage("ada@co.ng is already on this account for Ada Okeke");
    }

    @Test
    void anImportIsCheckedRowByRowIncludingWithinItselfAndAddsAllOrNone() {
        when(members.findByOrganizationIdOrderByFullName("o1")).thenReturn(List.of());
        assertThatThrownBy(() -> service.importMembers("o1", List.of(
                new MemberRequest("Ada Okeke", null, "08035550192", null),
                new MemberRequest("Bayo Ade", null, "08035550192", null))))
                .isInstanceOf(BadRequestException.class).hasMessage("Row 2: 08035550192 is already on this account for Ada Okeke");
        assertThatThrownBy(() -> service.importMembers("o1", List.of(new MemberRequest("1234", null, "08035550192", null))))
                .isInstanceOf(BadRequestException.class).hasMessage("Row 1: Enter the person's full name");
        verify(members, never()).saveAll(any());
    }
}
