package com.hospio.property.repository;

import com.hospio.property.entity.RoomType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, String> {
    List<RoomType> findByHotelIdOrderByName(String hotelId);

    long countByHotelIdAndActiveTrue(String hotelId);

    /** Another active type in this hotel with this name (trimmed, single-spaced, any case); {@code id} is the type being edited, or ''. */
    @org.springframework.data.jpa.repository.Query(value = "select name from property.room_types where hotel_id = :hotelId and active and id <> :id "
            + "and lower(regexp_replace(trim(name), '\\s+', ' ', 'g')) = :key limit 1", nativeQuery = true)
    java.util.Optional<String> otherActiveWithName(@org.springframework.data.repository.query.Param("hotelId") String hotelId,
                                                   @org.springframework.data.repository.query.Param("id") String id,
                                                   @org.springframework.data.repository.query.Param("key") String key);

    java.util.Optional<RoomType> findByPriceApprovalId(String priceApprovalId);
}
