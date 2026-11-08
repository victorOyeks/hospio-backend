package com.hospio.finance.repository;

import com.hospio.finance.entity.Budget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, String> {
    List<Budget> findByHotelIdAndMonth(String hotelId, String month);
}
