package com.hospio.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.entity.PurchaseOrder;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.entity.Supplier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stock levels under concurrency in a real Postgres: the first use of a level by two writers at once makes one row, and draws made at
 * the same time through the row lock all land (no lost decrement, never below zero). Also the order-number and unpaged-list queries.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockLevelLockingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("currentSchema", "inventory")
            .withCopyToContainer(Transferable.of("CREATE SCHEMA IF NOT EXISTS inventory;"), "/docker-entrypoint-initdb.d/00-schema.sql");

    static final String BIZ = "biz-1";
    static final String HOTEL = "hotel-lock";

    @Autowired
    StockLevelRepository levels;
    @Autowired
    StockItemRepository items;
    @Autowired
    StoreRepository stores;
    @Autowired
    SupplierRepository suppliers;
    @Autowired
    PurchaseOrderRepository orders;
    @Autowired
    PlatformTransactionManager txm;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        TenantContext.set(Tenant.systemFor(BIZ, HOTEL, "u1", "Jide"));
        tx = new TransactionTemplate(txm);
    }

    @AfterEach
    void out() {
        TenantContext.clear();
    }

    private String[] itemAndStore() {
        Store s = stores.saveAndFlush(Store.builder().businessId(BIZ).hotelId(HOTEL).name("Main " + System.nanoTime()).build());
        StockItem i = items.saveAndFlush(StockItem.builder().businessId(BIZ).hotelId(HOTEL).name("Rice " + System.nanoTime()).build());
        return new String[]{i.getId(), s.getId()};
    }

    private <T> List<T> together(int n, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                fs.add(pool.submit(() -> {
                    TenantContext.set(Tenant.systemFor(BIZ, HOTEL, "u1", "Jide"));
                    try {
                        go.await();
                        return work.call();
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theFirstUseByManyWritersAtOnceMakesOneRow() throws Exception {
        String[] is = itemAndStore();
        List<String> ids = together(6, () -> tx.execute(st -> levels.lockLevel(BIZ, HOTEL, is[0], is[1]).getId()));
        assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
        assertThat(levels.findByItemIdAndStoreId(is[0], is[1])).get().extracting(StockLevel::getQuantity)
                .satisfies(q -> assertThat(q).isEqualByComparingTo("0"));
    }

    @Test
    void drawsMadeAtTheSameTimeAllLandAndNeverGoBelowZero() throws Exception {
        String[] is = itemAndStore();
        tx.executeWithoutResult(st -> levels.lockLevel(BIZ, HOTEL, is[0], is[1]).setQuantity(new BigDecimal("10.00")));
        // 15 writers each take 1 from 10: exactly 10 succeed, the rest see what is really there.
        List<Boolean> took = together(15, () -> tx.execute(st -> {
            StockLevel l = levels.lockLevel(BIZ, HOTEL, is[0], is[1]);
            BigDecimal after = l.getQuantity().subtract(BigDecimal.ONE);
            if (after.signum() < 0) {
                return false;
            }
            l.setQuantity(after);
            return true;
        }));
        assertThat(took.stream().filter(b -> b).count()).isEqualTo(10);
        assertThat(levels.findByItemIdAndStoreId(is[0], is[1]).orElseThrow().getQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void aLevelMovedEarlierInTheTransactionIsNotLostWhenLockedAgain() {
        String[] is = itemAndStore();
        BigDecimal q = tx.execute(st -> {
            levels.lockLevel(BIZ, HOTEL, is[0], is[1]).setQuantity(new BigDecimal("4.00"));
            levels.lockLevels(List.of(is[0]), List.of(is[1]));
            return levels.lockLevel(BIZ, HOTEL, is[0], is[1]).getQuantity();
        });
        assertThat(q).isEqualByComparingTo("4.00");
    }

    @Test
    void orderNumbersAndTheUnpagedListQueries() {
        Supplier sup = suppliers.saveAndFlush(Supplier.builder().businessId(BIZ).hotelId(HOTEL).name("Foods Ltd").build());
        orders.saveAndFlush(PurchaseOrder.builder().businessId(BIZ).hotelId(HOTEL).number("PO-2026-00007").supplierId(sup.getId()).lines("[]")
                .status(PurchaseOrder.Status.SENT).build());
        orders.saveAndFlush(PurchaseOrder.builder().businessId(BIZ).hotelId(HOTEL).number("PO-2026-00003-2").supplierId(sup.getId()).lines("[]")
                .status(PurchaseOrder.Status.RECEIVED).build());
        orders.saveAndFlush(PurchaseOrder.builder().businessId(BIZ).hotelId(HOTEL).number("PO-2025-00090").supplierId(sup.getId()).lines("[]")
                .status(PurchaseOrder.Status.CLOSED).build());
        int last = tx.execute(st -> {
            orders.lockNumbers("inventory.po-number:" + HOTEL);
            return orders.lastNumber(HOTEL, "2026");
        });
        assertThat(last).isEqualTo(7);
        assertThat(orders.lastNumber(HOTEL, "2027")).isZero();

        EnumSet<PurchaseOrder.Status> open = EnumSet.of(PurchaseOrder.Status.DRAFT, PurchaseOrder.Status.PENDING_APPROVAL,
                PurchaseOrder.Status.SENT, PurchaseOrder.Status.PARTIAL);
        assertThat(orders.recentOrOpen(HOTEL, Instant.now().plus(1, ChronoUnit.DAYS), open)).extracting(PurchaseOrder::getNumber)
                .containsExactly("PO-2026-00007");
        assertThat(orders.recentOrOpen(HOTEL, Instant.now().minus(1, ChronoUnit.DAYS), open)).hasSize(3);
        List<Object[]> stats = orders.supplierStats(HOTEL, open);
        assertThat(stats).hasSize(1);
        assertThat(((Number) stats.get(0)[1]).intValue()).isEqualTo(1);
        assertThat(stats.get(0)[2]).isNotNull();
    }
}
