package com.hospio.platform.repository;

import com.hospio.platform.entity.FeeCollection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeCollectionRepository extends JpaRepository<FeeCollection, String> {

    Optional<FeeCollection> findByHotelId(String hotelId);

    List<FeeCollection> findByBusinessId(String businessId);

    List<FeeCollection> findAll();
}
