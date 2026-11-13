package com.hospio.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.tenancy.RowLevelSecurityDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One instance at a time: a scheduled job runs only under its advisory lock, and the lock and connection always go back. */
class JobLockTest {

    private DataSource pool;
    private Connection connection;
    private PreparedStatement lockStatement;
    private PreparedStatement unlockStatement;
    private PreparedStatement unlockAllStatement;
    private ResultSet lockResult;
    private ResultSet unlockResult;
    private JobLock jobLock;

    @BeforeEach
    void setUp() throws Exception {
        pool = mock(DataSource.class);
        connection = mock(Connection.class);
        lockStatement = mock(PreparedStatement.class);
        unlockStatement = mock(PreparedStatement.class);
        unlockAllStatement = mock(PreparedStatement.class);
        lockResult = mock(ResultSet.class);
        unlockResult = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")).thenReturn(lockStatement);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")).thenReturn(unlockStatement);
        when(connection.prepareStatement("SELECT pg_advisory_unlock_all()")).thenReturn(unlockAllStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(unlockStatement.executeQuery()).thenReturn(unlockResult);
        when(lockResult.next()).thenReturn(true);
        when(unlockResult.next()).thenReturn(true);
        when(unlockResult.getBoolean(1)).thenReturn(true);
        // Wrapped as in every service: the lock must be taken on the pool underneath, with no tenant bound.
        jobLock = new JobLock(new RowLevelSecurityDataSource(pool));
    }

    @Test
    void theJobRunsWhenTheLockIsFreeAndTheLockIsReleased() throws Throwable {
        when(lockResult.getBoolean(1)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        JobLock.Result<Integer> result = jobLock.runIfFree("job:x.run", () -> runs.incrementAndGet());

        assertThat(result.ran()).isTrue();
        assertThat(result.value()).isEqualTo(1);
        assertThat(runs).hasValue(1);
        verify(lockStatement).setString(1, "job:x.run");
        verify(unlockStatement).setString(1, "job:x.run");
        verify(connection).close();
    }

    @Test
    void theJobIsSkippedWhenAnotherInstanceHoldsTheLock() throws Throwable {
        when(lockResult.getBoolean(1)).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        JobLock.Result<Integer> result = jobLock.runIfFree("job:x.run", () -> runs.incrementAndGet());

        assertThat(result.ran()).isFalse();
        assertThat(runs).hasValue(0);
        verify(connection, never()).prepareStatement("SELECT pg_advisory_unlock(hashtext(?))");
        verify(connection).close();
    }

    @Test
    void aFailingJobStillUnlocksAndReturnsTheConnection() throws Throwable {
        when(lockResult.getBoolean(1)).thenReturn(true);

        assertThatThrownBy(() -> jobLock.runIfFree("job:x.run", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        verify(unlockStatement).setString(1, "job:x.run");
        verify(connection).close();
    }

    @Test
    void aFailedUnlockClearsTheSessionsLocksBeforeTheConnectionGoesBack() throws Throwable {
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(unlockStatement.executeQuery()).thenThrow(new java.sql.SQLException("gone"));

        jobLock.runIfFree("job:x.run", () -> "done");

        verify(unlockAllStatement).execute();
        verify(connection).close();
    }

    @Test
    void aJobCallingItselfIsNotLockedOutByItsOwnRun() throws Throwable {
        when(lockResult.getBoolean(1)).thenReturn(true);

        JobLock.Result<String> outer = jobLock.runIfFree("job:x.run", () -> jobLock.runIfFree("job:x.run", () -> "inner").value());

        assertThat(outer.value()).isEqualTo("inner");
        verify(pool, times(1)).getConnection();
        verify(lockStatement, times(1)).setString(1, "job:x.run");
    }

    @Test
    void theSkippedRunAnswersZeroForPrimitiveResults() throws Exception {
        assertThat(SingleInstanceScheduling.emptyValue(int.class)).isEqualTo(0);
        assertThat(SingleInstanceScheduling.emptyValue(boolean.class)).isEqualTo(false);
        assertThat(SingleInstanceScheduling.emptyValue(void.class)).isNull();
        assertThat(SingleInstanceScheduling.emptyValue(Integer.class)).isNull();
        verify(pool, never()).getConnection(anyString(), anyString());
    }
}
