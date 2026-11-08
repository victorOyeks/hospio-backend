package com.hospio.payment.repository;

import com.hospio.payment.entity.Dispute;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeRepository extends JpaRepository<Dispute, String> {
    List<Dispute> findByHotelIdOrderByCreatedAtDesc(String hotelId);
}
