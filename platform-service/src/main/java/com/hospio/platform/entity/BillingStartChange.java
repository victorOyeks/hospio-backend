package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One change of a business's "Charges start on" date, kept for good (the Money tab's history). Platform-only table. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "billing_start_changes")
public class BillingStartChange extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "before_on")
    private LocalDate beforeOn;

    @Column(name = "after_on")
    private LocalDate afterOn;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "made_by", length = 36)
    private String madeBy;

    @Column(name = "made_by_name", length = 150)
    private String madeByName;

    /** Days not on an invoice yet that the change worked out again. */
    @Column(name = "days_redone", nullable = false)
    private int daysRedone;
}
