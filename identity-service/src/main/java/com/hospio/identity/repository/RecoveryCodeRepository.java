package com.hospio.identity.repository;

import com.hospio.identity.entity.RecoveryCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, String> {

    Optional<RecoveryCode> findByUserIdAndCodeHash(String userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(String userId);

    List<RecoveryCode> findByUserId(String userId);

    @Modifying
    @Query("delete from RecoveryCode c where c.userId = :userId")
    void deleteByUser(@Param("userId") String userId);
}
