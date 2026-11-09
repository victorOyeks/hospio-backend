package com.hospio.folio.repository;

import com.hospio.folio.entity.FolioLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolioLineRepository extends JpaRepository<FolioLine, String> {
    List<FolioLine> findByFolioIdOrderByPostedAt(String folioId);

    List<FolioLine> findByFolioIdInOrderByPostedAt(java.util.Collection<String> folioIds);

    Optional<FolioLine> findBySourceModuleAndSourceRef(String sourceModule, String sourceRef);
}
