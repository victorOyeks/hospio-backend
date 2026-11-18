package com.hospio.platform.repository;

import com.hospio.platform.entity.LegalDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, String> {

    List<LegalDocument> findAllByOrderByDocKeyAscEffectiveFromDescCreatedAtDesc();

    Optional<LegalDocument> findByDocKeyAndVersion(String docKey, String version);
}
