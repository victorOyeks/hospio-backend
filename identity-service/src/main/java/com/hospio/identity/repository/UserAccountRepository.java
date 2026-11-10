package com.hospio.identity.repository;

import com.hospio.identity.entity.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<UserAccount> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);

    Optional<UserAccount> findByPhone(String phone);

    /** Accounts whose phone ends in these ten digits, however it was typed (see Phones.key). */
    @Query(value = "select count(*) from identity.user_accounts where phone is not null and right(regexp_replace(phone, '[^0-9]', '', 'g'), 10) = :key",
            nativeQuery = true)
    long countByPhoneKey(@Param("key") String key);

    List<UserAccount> findByBusinessIdOrderByFullName(String businessId);

    /** Timed pauses that have run out (Owner portal · Staff, pause for N days). */
    List<UserAccount> findByStatusAndPausedUntilLessThanEqual(UserAccount.Status status, java.time.Instant now);

    /** Platform staff and marketers: the accounts with no business. */
    List<UserAccount> findByBusinessIdIsNullOrderByFullName();
}
