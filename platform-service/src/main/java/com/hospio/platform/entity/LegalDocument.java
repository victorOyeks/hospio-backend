package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One version of a legal document people accept online (docs/legal-acceptance.md). The current version of a document is the newest one
 * whose {@code effectiveFrom} has passed; a version with a later date is announced (notice) and becomes required on that date.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "legal_documents")
public class LegalDocument extends BaseEntity {

    @Column(name = "doc_key", nullable = false, length = 20)
    private String docKey;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 60)
    private String slug;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(nullable = false)
    private boolean material;

    @Column(length = 500)
    private String summary;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;
}
