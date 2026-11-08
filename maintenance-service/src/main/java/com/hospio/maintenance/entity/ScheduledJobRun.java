package com.hospio.maintenance.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_job_runs")
public class ScheduledJobRun extends TenantEntity {

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    @Column(name = "done_on", nullable = false)
    private LocalDate doneOn;

    @Column(length = 1000)
    private String note;

    @Column(name = "done_by", length = 36)
    private String doneBy;
}
