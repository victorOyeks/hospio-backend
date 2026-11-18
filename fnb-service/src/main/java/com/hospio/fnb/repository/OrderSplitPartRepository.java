package com.hospio.fnb.repository;

import com.hospio.fnb.entity.OrderSplitPart;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSplitPartRepository extends JpaRepository<OrderSplitPart, String> {
    List<OrderSplitPart> findBySplitIdOrderByPosition(String splitId);

    List<OrderSplitPart> findBySplitIdInOrderByPosition(Collection<String> splitIds);
}
