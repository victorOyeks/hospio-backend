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

    /** The order with its row locked until the transaction ends: every change to an order takes this before its checks. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(@org.springframework.data.repository.query.Param("id") String id);

    /** What a change needs to know before it calls other services, read without loading (or locking) the order. */
    interface Peek {
        String getHotelId();

        String getOutletId();

        String getRoomId();

        String getRoomNumber();
    }

    @org.springframework.data.jpa.repository.Query("select o.hotelId as hotelId, o.outletId as outletId, o.roomId as roomId, o.roomNumber as roomNumber "
            + "from Order o where o.id = :id")
    Optional<Peek> peek(@org.springframework.data.repository.query.Param("id") String id);

    long countByOutletIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(String outletId, java.time.Instant from, java.time.Instant to);

    /** Orders settled (paid, charged to a room or closed as complimentary) in the window. */
    List<Order> findByHotelIdAndSettledAtGreaterThanEqualAndSettledAtLessThan(String hotelId, java.time.Instant from, java.time.Instant to);

    /** Orders cancelled in the window. */
    List<Order> findByHotelIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThan(String hotelId, Order.Status status, java.time.Instant from, java.time.Instant to);

    /** Orders closed in the window at one outlet, whatever way they ended. */
    List<Order> findByOutletIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByClosedAtDesc(String outletId, Order.Status status, java.time.Instant from, java.time.Instant to);

    List<Order> findByHotelIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByClosedAtDesc(String hotelId, Order.Status status, java.time.Instant from, java.time.Instant to);
}
