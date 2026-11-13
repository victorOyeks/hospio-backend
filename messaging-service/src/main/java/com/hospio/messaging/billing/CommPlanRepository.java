package com.hospio.messaging.billing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommPlanRepository extends JpaRepository<CommPlan, String> {

    Optional<CommPlan> findByHotelId(String hotelId);

    List<CommPlan> findByBusinessId(String businessId);

    List<CommPlan> findByHotelIdIn(java.util.Collection<String> hotelIds);
}
