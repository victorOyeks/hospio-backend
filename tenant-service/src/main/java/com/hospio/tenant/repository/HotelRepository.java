package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Hotel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, String> {
    List<Hotel> findByBusinessIdOrderByName(String businessId);

    int countByBusinessId(String businessId);

    /** Another hotel of this business with this name (trimmed, single-spaced, any case); {@code id} is the one being edited, or ''. */
    @org.springframework.data.jpa.repository.Query(value = "select name from tenant.hotels where business_id = :businessId and id <> :id "
            + "and lower(regexp_replace(trim(name), '\\s+', ' ', 'g')) = :key limit 1", nativeQuery = true)
    java.util.Optional<String> otherWithName(@org.springframework.data.repository.query.Param("businessId") String businessId,
                                             @org.springframework.data.repository.query.Param("id") String id,
                                             @org.springframework.data.repository.query.Param("key") String key);

    List<Hotel> findByBusinessIdIn(java.util.Collection<String> businessIds);

    /** Hotels and rooms of the businesses a search matches ({@code q} is a lower-case like pattern, or '' for every business). */
    @org.springframework.data.jpa.repository.Query("select count(h), coalesce(sum(h.roomCount), 0) from Hotel h where h.businessId in "
            + "(select b.id from Business b where :q = '' or lower(b.name) like :q or lower(b.ownerName) like :q or lower(b.ownerEmail) like :q "
            + "or lower(b.city) like :q)")
    List<Object[]> hotelsAndRooms(@org.springframework.data.repository.query.Param("q") String q);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(h.roomCount), 0) from Hotel h")
    long sumRoomCount();
}
