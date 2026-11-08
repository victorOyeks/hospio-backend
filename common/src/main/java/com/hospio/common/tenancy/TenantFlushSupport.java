package com.hospio.common.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Lets {@link TenantContext#runWith} push pending JPA changes to the database before the tenant is
 * switched back. The row-level policies are evaluated with the session settings bound at statement
 * time, so a write made "as the platform" inside a user's request has to reach Postgres while the
 * platform scope is still bound, not at the commit that happens after the block has left it.
 */
@Component
public class TenantFlushSupport {

    private static volatile EntityManagerFactory factory;

    public TenantFlushSupport(ObjectProvider<EntityManagerFactory> emf) {
        factory = emf.getIfAvailable();
    }

    /** Flushes the transactional EntityManager of the current thread, if there is one. No-op otherwise. */
    static void flushIfActive() {
        EntityManagerFactory emf = factory;
        if (emf == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
        if (em != null && em.isOpen() && em.isJoinedToTransaction()) {
            em.flush();
        }
    }
}
