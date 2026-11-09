package com.hospio.fnb.repository;

import com.hospio.fnb.entity.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByOutletIdAndStatusInOrderByCreatedAt(String outletId, java.util.Collection<Order.Status> statuses);

    List<Order> findByHotelIdAndStatusInOrderByCreatedAt(String hotelId, java.util.Collection<Order.Status> statuses);

    List<Order> findByHotelIdAndCreatedAtAfterOrderByCreatedAtDesc(String hotelId, java.time.Instant since);

    long countByHotelId(String hotelId);

    long countByOutletIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(String outletId, java.time.Instant from, java.time.Instant to);

    /** Orders settled (paid, charged to a room or closed as complimentary) in the window. */
    List<Order> findByHotelIdAndSettledAtGreaterThanEqualAndSettledAtLessThan(String hotelId, java.time.Instant from, java.time.Instant to);

    /** Orders cancelled in the window. */
    List<Order> findByHotelIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThan(String hotelId, Order.Status status, java.time.Instant from, java.time.Instant to);

    /** Orders closed in the window at one outlet, whatever way they ended. */
    List<Order> findByOutletIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByClosedAtDesc(String outletId, Order.Status status, java.time.Instant from, java.time.Instant to);

    List<Order> findByHotelIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByClosedAtDesc(String hotelId, Order.Status status, java.time.Instant from, java.time.Instant to);
}
