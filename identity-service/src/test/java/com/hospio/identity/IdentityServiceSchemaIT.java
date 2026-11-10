package com.hospio.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the service against a real Postgres: Liquibase builds the identity schema from the
 * changelog, Hibernate validates the entities against it, and the row-level policies are in place.
 * Needs Docker; skipped where there is none.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class IdentityServiceSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("identity-schema.sql");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void schemaIsBuiltAndProtected() {
        Integer tables = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'identity'", Integer.class);
        assertThat(tables).isGreaterThanOrEqualTo(4);
        Boolean rls = jdbc.queryForObject("select relrowsecurity from pg_class where oid = 'identity.user_accounts'::regclass", Boolean.class);
        assertThat(rls).isTrue();
    }
}
