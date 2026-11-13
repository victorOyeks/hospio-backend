package com.hospio.finance.repository;

import com.hospio.finance.entity.RevenueEntry;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevenueEntryRepository extends JpaRepository<RevenueEntry, String> {
    Optional<RevenueEntry> findByPaymentId(String paymentId);

    List<RevenueEntry> findByHotelIdAndBusinessDate(String hotelId, java.time.LocalDate date);

    List<RevenueEntry> findByHotelIdAndBusinessDateBetween(String hotelId, java.time.LocalDate from, java.time.LocalDate to);

    /** [hotelId, sum] per hotel between two business days (both inclusive). */
    @Query("select r.hotelId, sum(r.amount) from RevenueEntry r where r.hotelId in :hotelIds and r.businessDate between :from and :to group by r.hotelId")
    List<Object[]> sumByHotel(@Param("hotelIds") Collection<String> hotelIds, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** [businessDate, sum] per day at a hotel. */
    @Query("select r.businessDate, sum(r.amount) from RevenueEntry r where r.hotelId = :hotelId and r.businessDate between :from and :to group by r.businessDate")
    List<Object[]> sumByDay(@Param("hotelId") String hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** [desk, role, sum] at a hotel: the income desk is worked out from the two (RevenueEntry.incomeDesk). */
    @Query("select r.desk, r.role, sum(r.amount) from RevenueEntry r where r.hotelId = :hotelId and r.businessDate between :from and :to group by r.desk, r.role")
    List<Object[]> sumByDeskAndRole(@Param("hotelId") String hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
