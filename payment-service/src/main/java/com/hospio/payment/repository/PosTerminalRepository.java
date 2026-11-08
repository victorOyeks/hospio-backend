package com.hospio.payment.repository;

import com.hospio.payment.entity.PosTerminal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosTerminalRepository extends JpaRepository<PosTerminal, String> {
    List<PosTerminal> findByHotelIdOrderByName(String hotelId);
}
