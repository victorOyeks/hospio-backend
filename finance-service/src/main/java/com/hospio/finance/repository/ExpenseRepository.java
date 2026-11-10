package com.hospio.finance.repository;

import com.hospio.finance.entity.Expense;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Expense> {
    List<Expense> findByHotelIdOrderByExpenseDateDesc(String hotelId);

    List<Expense> findByHotelIdAndExpenseDateBetween(String hotelId, java.time.LocalDate from, java.time.LocalDate to);

    Optional<Expense> findByApprovalId(String approvalId);

    List<Expense> findByHotelIdAndStatusIn(String hotelId, java.util.Collection<Expense.Status> statuses);

    List<Expense> findByHotelIdAndPaidAtBetween(String hotelId, java.time.Instant from, java.time.Instant to);

    /** Every spelling of a category this hotel's expenses carry (bounded by the category list), for the category filter. */
    @org.springframework.data.jpa.repository.Query("select distinct e.category from Expense e where e.hotelId = :hotelId")
    List<String> distinctCategories(@org.springframework.data.repository.query.Param("hotelId") String hotelId);

    /** Money spent per stored category between two days (both inclusive), counting only the given statuses. */
    @org.springframework.data.jpa.repository.Query("select e.category, sum(e.amount) from Expense e where e.hotelId = :hotelId "
            + "and e.expenseDate between :from and :to and e.status in :statuses group by e.category")
    List<Object[]> spendByCategory(@org.springframework.data.repository.query.Param("hotelId") String hotelId,
                                   @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
                                   @org.springframework.data.repository.query.Param("to") java.time.LocalDate to,
                                   @org.springframework.data.repository.query.Param("statuses") java.util.Collection<Expense.Status> statuses);
}
