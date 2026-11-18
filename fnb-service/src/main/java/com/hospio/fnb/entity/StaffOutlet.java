package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** An outlet a person works. No rows for someone: every outlet of the location. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "staff_outlets")
public class StaffOutlet extends TenantEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;
}
