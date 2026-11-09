package com.hospio.fnb.repository;

import com.hospio.fnb.entity.Outlet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutletRepository extends JpaRepository<Outlet, String> {
    List<Outlet> findByHotelIdOrderByName(String hotelId);

    /** Locks the outlet row so two orders opened at once get different numbers. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Outlet o where o.id = :id")
    Optional<Outlet> lockById(@org.springframework.data.repository.query.Param("id") String id);
}
