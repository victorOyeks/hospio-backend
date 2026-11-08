package com.hospio.finance.repository;

import com.hospio.finance.entity.Expense;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, String> {
    List<Expense> findByHotelIdOrderByExpenseDateDesc(String hotelId);

    List<Expense> findByHotelIdAndExpenseDateBetween(String hotelId, java.time.LocalDate from, java.time.LocalDate to);

    Optional<Expense> findByApprovalId(String approvalId);
}
