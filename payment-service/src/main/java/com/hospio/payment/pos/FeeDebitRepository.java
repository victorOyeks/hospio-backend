package com.hospio.payment.pos;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeDebitRepository extends JpaRepository<FeeDebit, String> {

    Optional<FeeDebit> findByReference(String reference);
}
