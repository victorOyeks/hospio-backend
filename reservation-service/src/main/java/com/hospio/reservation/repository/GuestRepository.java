package com.hospio.reservation.repository;

import com.hospio.reservation.entity.Guest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestRepository extends JpaRepository<Guest, String> {

    Optional<Guest> findFirstByBusinessIdAndPhone(String businessId, String phone);

    /** The business's guest with this number however it was written: the last ten digits match (oldest first). */
    @Query(value = "select * from reservation.guests g where g.business_id = :business "
            + "and right(regexp_replace(coalesce(g.phone, ''), '[^0-9]', '', 'g'), 10) = :key order by g.created_at limit 1", nativeQuery = true)
    Optional<Guest> findByPhoneKey(@Param("business") String businessId, @Param("key") String key);

    /** By name, phone or email; {@code digits} (blank for none) also matches the phone's digits, so "0803…" finds "+234 803 …". */
    @Query(value = "select * from reservation.guests g where g.business_id = :business and (lower(g.full_name) like lower(concat('%', :q, '%')) "
            + "or coalesce(g.phone, '') like concat('%', :q, '%') or lower(coalesce(g.email, '')) like lower(concat('%', :q, '%')) "
            + "or (:digits <> '' and regexp_replace(coalesce(g.phone, ''), '[^0-9]', '', 'g') like concat('%', :digits, '%'))) "
            + "order by g.full_name", nativeQuery = true)
    List<Guest> search(@Param("business") String businessId, @Param("q") String query, @Param("digits") String digits);
}
