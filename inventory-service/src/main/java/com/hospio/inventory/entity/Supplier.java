package com.hospio.inventory.entity;

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
@Table(name = "suppliers")
public class Supplier extends TenantEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    private String phone;
    private String email;

    @Column(name = "payment_terms", length = 100)
    private String paymentTerms;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    @Column(name = "address", length = 300)
    private String address;

    @Column(name = "notes", length = 500)
    private String notes;
}
