package com.hospio.finance.repository;

import com.hospio.finance.entity.ExpenseCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, String> {
    List<ExpenseCategory> findByHotelIdOrderBySortOrderAscLabelAsc(String hotelId);
}
