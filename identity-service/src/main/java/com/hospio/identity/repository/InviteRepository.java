package com.hospio.identity.repository;

import com.hospio.identity.entity.Invite;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteRepository extends JpaRepository<Invite, String> {
    Optional<Invite> findByTokenHash(String tokenHash);

    List<Invite> findByUserIdInOrderByCreatedAtDesc(Collection<String> userIds);
}
