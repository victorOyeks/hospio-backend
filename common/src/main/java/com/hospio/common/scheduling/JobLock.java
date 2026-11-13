package com.hospio.common.scheduling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Runs a piece of work on one instance at a time across every copy of a service, using a Postgres session-level
 * advisory lock ({@code pg_try_advisory_lock(hashtext(key))}) held on a dedicated pooled connection for the length
 * of the run. When another instance holds the lock the run is skipped, not queued: the next tick tries again.
 *
 * The lock connection is taken from the pool underneath the row-level-security wrapper, so no tenant is bound to it,
 * and it is always unlocked and returned, also when the work throws. If the unlock itself fails the session's
 * advisory locks are dropped with {@code pg_advisory_unlock_all()} before the connection goes back, so a pooled
 * connection never keeps a job locked forever.
 */
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    /** Keys this thread already holds: a job that calls itself (or another proxy of itself) is not locked out by its own run. */
    private static final ThreadLocal<Set<String>> HELD = ThreadLocal.withInitial(HashSet::new);

    private final DataSource dataSource;

    public JobLock(DataSource dataSource) {
        this.dataSource = unwrap(dataSource);
    }

    /** The work to run under the lock. */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws Throwable;
    }

    /** The outcome: whether the lock was free (and the work ran) and what it returned. */
    public record Result<T>(boolean ran, T value) {
    }

    /**
     * Runs {@code work} if no other session holds {@code key}; otherwise returns {@code ran == false} without running it.
     * A failure to reach the database to take the lock is thrown (the run did not happen).
     */
    public <T> Result<T> runIfFree(String key, Work<T> work) throws Throwable {
        Set<String> held = HELD.get();
        if (held.contains(key)) {
            return new Result<>(true, work.run());
        }
        Connection connection = dataSource.getConnection();
        try {
            if (!tryLock(connection, key)) {
                log.debug("Skipped {}: running on another instance", key);
                return new Result<>(false, null);
            }
            held.add(key);
            try {
                return new Result<>(true, work.run());
            } finally {
                held.remove(key);
                unlock(connection, key);
            }
        } finally {
            if (held.isEmpty()) {
                HELD.remove();
            }
            connection.close();
        }
    }

    private static boolean tryLock(Connection connection, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, String key) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    return;
                }
            }
            log.warn("Lock {} was not held at unlock; clearing the session's advisory locks", key);
        } catch (SQLException e) {
            log.warn("Could not unlock {}: {}; clearing the session's advisory locks", key, e.getMessage());
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock_all()")) {
            ps.execute();
        } catch (SQLException e) {
            // A connection that cannot run this is broken; the pool evicts it and Postgres drops its locks with the session.
            log.warn("Could not clear advisory locks after {}: {}", key, e.getMessage());
        }
    }

    /** The pool beneath the tenant-binding wrapper(s): the lock statement needs no tenant and should not set one. */
    private static DataSource unwrap(DataSource dataSource) {
        DataSource ds = dataSource;
        while (ds instanceof DelegatingDataSource delegating && delegating.getTargetDataSource() != null) {
            ds = delegating.getTargetDataSource();
        }
        return ds;
    }
}
