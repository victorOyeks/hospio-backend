package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Hotel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, String> {
    List<Hotel> findByBusinessIdOrderByName(String businessId);

    int countByBusinessId(String businessId);

    List<Hotel> findByBusinessIdIn(java.util.Collection<String> businessIds);

    /** Hotels and rooms of the businesses a search matches ({@code q} is a lower-case like pattern, or '' for every business). */
    @org.springframework.data.jpa.repository.Query("select count(h), coalesce(sum(h.roomCount), 0) from Hotel h where h.businessId in "
            + "(select b.id from Business b where :q = '' or lower(b.name) like :q or lower(b.ownerName) like :q or lower(b.ownerEmail) like :q "
            + "or lower(b.city) like :q)")
    List<Object[]> hotelsAndRooms(@org.springframework.data.repository.query.Param("q") String q);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(h.roomCount), 0) from Hotel h")
    long sumRoomCount();
}
