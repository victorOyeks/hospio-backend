package com.hospio.folio.repository;

import com.hospio.folio.entity.FolioLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolioLineRepository extends JpaRepository<FolioLine, String> {
    List<FolioLine> findByFolioIdOrderByPostedAt(String folioId);

    List<FolioLine> findByFolioIdInOrderByPostedAt(java.util.Collection<String> folioIds);

    Optional<FolioLine> findBySourceModuleAndSourceRef(String sourceModule, String sourceRef);

    /** Per kind, the sum of amount and tax over the folio's lines that are not voided: one row per kind present. */
    @org.springframework.data.jpa.repository.Query("select l.kind, coalesce(sum(l.amount), 0), coalesce(sum(l.taxAmount), 0) from FolioLine l "
            + "where l.folioId = :folioId and l.voided = false group by l.kind")
    List<Object[]> totalsByKind(@org.springframework.data.repository.query.Param("folioId") String folioId);
}
