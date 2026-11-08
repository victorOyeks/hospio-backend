package com.hospio.reservation.repository;

import com.hospio.reservation.entity.Guest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestRepository extends JpaRepository<Guest, String> {

    Optional<Guest> findFirstByBusinessIdAndPhone(String businessId, String phone);

    @Query("select g from Guest g where g.businessId = :business and (lower(g.fullName) like lower(concat('%', :q, '%')) "
            + "or g.phone like concat('%', :q, '%') or lower(g.email) like lower(concat('%', :q, '%'))) order by g.fullName")
    List<Guest> search(@Param("business") String businessId, @Param("q") String query);
}
