package com.hospio.finance.repository;

import com.hospio.finance.entity.BankDeposit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankDepositRepository extends JpaRepository<BankDeposit, String> {
    List<BankDeposit> findByHotelIdOrderByBankedAtDesc(String hotelId);

    List<BankDeposit> findByHotelIdAndBankedAtBetween(String hotelId, java.time.Instant from, java.time.Instant to);
}
