package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ExpenseRecordedEvent extends HospioEvent {
    private String expenseId;
    private String category;
    private java.math.BigDecimal amount;
    private String description;
    private String status;

    @Override
    public String summary() {
        return "Expense of " + amount + " · " + category + " · " + status;
    }
}
