package com.hospio.folio.repository;

import com.hospio.folio.entity.OrganizationMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, String> {
    List<OrganizationMember> findByOrganizationIdOrderByFullName(String organizationId);
}
