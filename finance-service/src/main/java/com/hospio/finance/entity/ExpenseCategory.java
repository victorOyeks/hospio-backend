package com.hospio.finance.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One of a hotel's expense categories: a stable code (stored on expenses, budgets and bills) and the label people read. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "expense_categories")
public class ExpenseCategory extends TenantEntity {

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}
