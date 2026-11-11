package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Business;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BusinessRepository extends JpaRepository<Business, String>, JpaSpecificationExecutor<Business> {
    List<Business> findByStageOrderByName(Business.Stage stage);

    List<Business> findAllByOrderByName();

    List<Business> findByMarketerIdOrderByName(String marketerId);

    /** Another business with this name (trimmed, single-spaced, any case); {@code id} is the one being edited, or '' for a new one. */
    @org.springframework.data.jpa.repository.Query(value = "select name from tenant.businesses where id <> :id "
            + "and lower(regexp_replace(trim(name), '\\s+', ' ', 'g')) = :key limit 1", nativeQuery = true)
    java.util.Optional<String> otherWithName(@org.springframework.data.repository.query.Param("id") String id,
                                             @org.springframework.data.repository.query.Param("key") String key);

    /** Another business with this CAC number however it was typed ("RC 1873402", "rc1873402", "1873402"). */
    @org.springframework.data.jpa.repository.Query(value = "select name from tenant.businesses where id <> :id and registration_number is not null "
            + "and upper(regexp_replace(registration_number, '[^A-Za-z0-9]', '', 'g')) in (:key, :digits) limit 1", nativeQuery = true)
    java.util.Optional<String> otherWithRegistration(@org.springframework.data.repository.query.Param("id") String id,
                                                     @org.springframework.data.repository.query.Param("key") String key,
                                                     @org.springframework.data.repository.query.Param("digits") String digits);

    /** Another business whose owner has this email (any case). */
    @org.springframework.data.jpa.repository.Query(value = "select name from tenant.businesses where id <> :id and lower(owner_email) = :email limit 1",
            nativeQuery = true)
    java.util.Optional<String> otherWithOwnerEmail(@org.springframework.data.repository.query.Param("id") String id,
                                                   @org.springframework.data.repository.query.Param("email") String email);

    /** Another business whose owner phone ends in these ten digits, however it was typed. */
    @org.springframework.data.jpa.repository.Query(value = "select name from tenant.businesses where id <> :id and owner_phone is not null "
            + "and right(regexp_replace(owner_phone, '[^0-9]', '', 'g'), 10) = :key limit 1", nativeQuery = true)
    java.util.Optional<String> otherWithOwnerPhone(@org.springframework.data.repository.query.Param("id") String id,
                                                   @org.springframework.data.repository.query.Param("key") String key);

    /** Serialises the uniqueness checks of two admins saving at once (released at commit). */
    @org.springframework.data.jpa.repository.Query(value = "select 1 from (select pg_advisory_xact_lock(hashtext('tenant.business-unique'))) l",
            nativeQuery = true)
    Integer lockUniqueChecks();
}
