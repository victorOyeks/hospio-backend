package com.hospio.identity.repository;

import com.hospio.common.enums.Role;
import com.hospio.identity.entity.RoleGrant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleGrantRepository extends JpaRepository<RoleGrant, String> {
    List<RoleGrant> findByUserId(String userId);

    List<RoleGrant> findByHotelIdAndRole(String hotelId, Role role);

    List<RoleGrant> findByBusinessId(String businessId);

    Optional<RoleGrant> findByUserIdAndHotelIdAndRole(String userId, String hotelId, Role role);
}
