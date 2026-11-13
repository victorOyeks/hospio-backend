package com.hospio.identity.repository;

import com.hospio.common.enums.Role;
import com.hospio.identity.entity.RoleGrant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleGrantRepository extends JpaRepository<RoleGrant, String> {
    List<RoleGrant> findByUserId(String userId);

    List<RoleGrant> findByUserIdIn(java.util.Collection<String> userIds);

    List<RoleGrant> findByHotelIdAndRole(String hotelId, Role role);

    List<RoleGrant> findByBusinessId(String businessId);

    List<RoleGrant> findByHotelId(String hotelId);

    List<RoleGrant> findByExpiresAtBefore(java.time.Instant now);

    Optional<RoleGrant> findByUserIdAndHotelIdAndRole(String userId, String hotelId, Role role);

    /** Deletes the grant only if it is still expired; 1 when this call removed it, 0 when someone else did (or it was extended). */
    @Modifying
    @Query("delete from RoleGrant g where g.id = :id and g.expiresAt < :now")
    int deleteIfExpired(@Param("id") String id, @Param("now") java.time.Instant now);
}
