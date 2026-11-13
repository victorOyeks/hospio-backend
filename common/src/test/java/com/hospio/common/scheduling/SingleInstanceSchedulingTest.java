package com.hospio.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/** The aspect wraps @Scheduled methods only when the scheduler runs them, and a skipped run returns zero, not null. */
class SingleInstanceSchedulingTest {

    public static class Jobs {
        final AtomicInteger runs = new AtomicInteger();

        @Scheduled(fixedDelay = 60_000)
        public int sweep() {
            return runs.incrementAndGet();
        }

        public int notScheduled() {
            return runs.incrementAndGet();
        }
    }

    private DataSource pool;
    private ResultSet lockResult;
    private Jobs target;
    private Jobs proxy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        pool = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        lockResult = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(pool);

        target = new Jobs();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new SingleInstanceScheduling(provider, "scheduling-"));
        proxy = factory.getProxy();
    }

    @Test
    void aSchedulerRunTakesTheLockAndRuns() throws Exception {
        when(lockResult.getBoolean(1)).thenReturn(true);
        assertThat(onSchedulerThread(() -> proxy.sweep())).isEqualTo(1);
        verify(pool).getConnection();
    }

    @Test
    void aSchedulerRunIsSkippedWhileAnotherInstanceHasTheLock() throws Exception {
        when(lockResult.getBoolean(1)).thenReturn(false);
        assertThat(onSchedulerThread(() -> proxy.sweep())).isEqualTo(0);
        assertThat(target.runs).hasValue(0);
    }

    @Test
    void aDirectCallIsNotLocked() throws Exception {
        assertThat(proxy.sweep()).isEqualTo(1);
        assertThat(onSchedulerThread(() -> proxy.notScheduled())).isEqualTo(2);
        verify(pool, never()).getConnection();
    }

    @Test
    void theKeyNamesTheClassAndMethod() throws Exception {
        assertThat(SingleInstanceScheduling.key(Jobs.class.getMethod("sweep")))
                .isEqualTo("job:com.hospio.common.scheduling.SingleInstanceSchedulingTest$Jobs.sweep");
    }

    private static int onSchedulerThread(java.util.function.IntSupplier call) throws InterruptedException {
        AtomicReference<Integer> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(call.getAsInt()), "scheduling-1");
        t.start();
        t.join();
        return result.get();
    }
}
