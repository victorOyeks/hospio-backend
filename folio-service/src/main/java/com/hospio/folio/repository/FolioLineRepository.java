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

    /**
     * The hotel's charges and discounts that can count on Lagos day [{@code from}, {@code to}) of {@code day}: posted that day, voided
     * that day, or a room line covering that night (or earlier nights, when it was posted late). The daily sales sort out what counts.
     */
    @org.springframework.data.jpa.repository.Query("select l from FolioLine l where l.hotelId = :hotelId and l.kind in ('CHARGE', 'DISCOUNT') and ("
            + "(l.postedAt >= :from and l.postedAt < :to) or (l.voidedAt >= :from and l.voidedAt < :to) "
            + "or (l.serviceFrom is not null and l.serviceFrom <= :day and l.serviceTo > :day))")
    java.util.List<FolioLine> salesCandidates(@org.springframework.data.repository.query.Param("hotelId") String hotelId,
                                              @org.springframework.data.repository.query.Param("from") java.time.Instant from,
                                              @org.springframework.data.repository.query.Param("to") java.time.Instant to,
                                              @org.springframework.data.repository.query.Param("day") java.time.LocalDate day);
}
