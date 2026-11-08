package com.hospio.identity.repository;

import com.hospio.identity.entity.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);

    Optional<UserAccount> findByPhone(String phone);

    List<UserAccount> findByBusinessIdOrderByFullName(String businessId);
}
