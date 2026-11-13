package com.hospio.common.scheduling;

import java.lang.reflect.Method;
import javax.sql.DataSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Every {@code @Scheduled} method of every service runs on one instance at a time: when the scheduler fires it, the
 * method runs only if this instance gets the Postgres advisory lock {@code job:<declaring class>.<method>} (see
 * {@link JobLock}); otherwise the tick is skipped with a debug line. No job has to opt in.
 *
 * Only runs started by the scheduler are locked (the scheduler's threads are named {@code spring.task.scheduling.
 * thread-name-prefix}, "scheduling-" by default): a controller or test calling the same method directly runs it as before.
 * The advice is outermost, so the lock is taken before any {@code @Transactional} on the job opens its transaction.
 * A service without a DataSource (the gateway) runs its jobs unlocked.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SingleInstanceScheduling {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceScheduling.class);

    private final ObjectProvider<DataSource> dataSources;
    private final String schedulerThreadPrefix;
    private volatile JobLock jobLock;

    public SingleInstanceScheduling(ObjectProvider<DataSource> dataSources,
                                    @Value("${spring.task.scheduling.thread-name-prefix:scheduling-}") String schedulerThreadPrefix) {
        // Resolved on the first run, not here: an aspect is built before most beans, and pulling the DataSource in now
        // would create it before the row-level-security post-processor could wrap it.
        this.dataSources = dataSources;
        this.schedulerThreadPrefix = schedulerThreadPrefix;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled) || @annotation(org.springframework.scheduling.annotation.Schedules)")
    public Object aroundScheduled(ProceedingJoinPoint pjp) throws Throwable {
        if (!Thread.currentThread().getName().startsWith(schedulerThreadPrefix)) {
            return pjp.proceed();
        }
        JobLock lock = lock();
        if (lock == null) {
            return pjp.proceed();
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String key = key(method);
        JobLock.Result<Object> result = lock.runIfFree(key, pjp::proceed);
        return result.ran() ? result.value() : emptyValue(method.getReturnType());
    }

    /** {@code job:<declaring class>.<method>}: the same on every instance of the service, different for every job. */
    static String key(Method method) {
        return "job:" + method.getDeclaringClass().getName() + "." + method.getName();
    }

    private JobLock lock() {
        JobLock current = jobLock;
        if (current == null) {
            DataSource ds = dataSources.getIfAvailable();
            if (ds == null) {
                log.debug("No DataSource: scheduled jobs run without a cross-instance lock");
                return null;
            }
            current = new JobLock(ds);
            jobLock = current;
        }
        return current;
    }

    /** What a skipped run returns: nothing, or the primitive's zero so the proxy does not fail on a null. */
    static Object emptyValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return 0;
    }
}
