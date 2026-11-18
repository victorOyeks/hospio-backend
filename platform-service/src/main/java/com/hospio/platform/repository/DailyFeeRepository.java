package com.hospio.platform.repository;

import com.hospio.platform.entity.DailyFee;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyFeeRepository extends JpaRepository<DailyFee, String> {

    Optional<DailyFee> findByHotelIdAndFeeDate(String hotelId, LocalDate feeDate);

    List<DailyFee> findByBusinessIdAndFeeDateBetweenOrderByFeeDateDescHotelNameAsc(String businessId, LocalDate from, LocalDate to);

    List<DailyFee> findByHotelIdAndFeeDateBetweenOrderByFeeDateDesc(String hotelId, LocalDate from, LocalDate to);

    List<DailyFee> findByFeeDateBetweenOrderByFeeDateDescHotelNameAsc(LocalDate from, LocalDate to);

    /** Each hotel's latest worked-out day. */
    @org.springframework.data.jpa.repository.Query("select f from DailyFee f where f.feeDate = (select max(g.feeDate) from DailyFee g where g.hotelId = f.hotelId)")
    List<DailyFee> latestPerHotel();

    List<DailyFee> findByStatusInAndFeeDateLessThanEqualOrderByFeeDateAsc(List<String> statuses, LocalDate upTo);

    /** The hotel's earlier days in these statuses (what they left unpaid is carried in). */
    List<DailyFee> findByHotelIdAndStatusInAndFeeDateBefore(String hotelId, List<String> statuses, LocalDate date);

    /** Days in a row, newest first, up to {@code date} (the POS pause counts unpaid days). */
    List<DailyFee> findTop5ByHotelIdAndFeeDateLessThanEqualOrderByFeeDateDesc(String hotelId, LocalDate date);

    /** The business's first day with a fee collected: the start of a marketer's 12 months (docs/plan-marketers.md). */
    @org.springframework.data.jpa.repository.Query("select min(f.feeDate) from DailyFee f where f.businessId = :businessId and f.collected > 0")
    LocalDate firstCollectedDate(@org.springframework.data.repository.query.Param("businessId") String businessId);

    /** The business's days up to {@code to} not on an invoice yet, in the statuses that are billed (ACCRUED, and the trial's SHADOW/NO_FEE). */
    List<DailyFee> findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(String businessId,
            List<String> statuses, LocalDate to);

    List<DailyFee> findByMonthlyInvoiceIdOrderByHotelNameAscFeeDateAsc(String monthlyInvoiceId);
}
