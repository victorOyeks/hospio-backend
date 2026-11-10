package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.dto.InventoryViews;
import com.hospio.inventory.entity.Delivery;
import com.hospio.inventory.entity.PurchaseOrder;
import com.hospio.inventory.entity.StockRequest;
import com.hospio.inventory.entity.Supplier;
import com.hospio.inventory.repository.DeliveryRepository;
import com.hospio.inventory.repository.PurchaseOrderRepository;
import com.hospio.inventory.repository.StockRequestRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The store's lists paged in a real Postgres (the service's Liquibase schema): who sees which requests, status aliases and counts over
 * the whole filtered range, the supplier-name and order-number searches, the delivery value, size above 100 refused.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InventoryLists.class, GroupCounts.class})
class InventoryListsQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("currentSchema", "inventory")
            .withCopyToContainer(Transferable.of("CREATE SCHEMA IF NOT EXISTS inventory;"), "/docker-entrypoint-initdb.d/00-schema.sql");

    static final String HOTEL = "hotel-1";

    @Autowired
    InventoryLists lists;
    @Autowired
    StockRequestRepository requests;
    @Autowired
    PurchaseOrderRepository orders;
    @Autowired
    DeliveryRepository deliveries;
    @Autowired
    com.hospio.inventory.repository.SupplierRepository suppliers;
    @MockBean
    InventoryService inventory;

    @BeforeEach
    void tenant() {
        TenantContext.set(Tenant.systemFor("biz-1", HOTEL, "u1", "Jide"));
    }

    @AfterEach
    void out() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    static void as(Role role, AccessLevel level) {
        HospioUserDetails u = new HospioUserDetails("u1", "j@x.ng", "Jide", "biz-1", List.of(HOTEL), Map.of(role, level), "HOTEL");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    static InventoryLists.Query page(String q, String status, int page, Integer size) {
        return InventoryLists.query(q, status, null, null, null, page, size);
    }

    void seedRequests() {
        for (int i = 0; i < 40; i++) {
            StockRequest.Status s = i < 12 ? StockRequest.Status.REQUESTED : i < 15 ? StockRequest.Status.PART_ISSUED : StockRequest.Status.ISSUED;
            requests.save(StockRequest.builder().businessId("biz-1").hotelId(HOTEL).fromTeam(i % 2 == 0 ? "KITCHEN" : "BAR")
                    .lines("[{\"name\":\"" + (i == 7 ? "Saffron" : "Rice") + "\",\"quantity\":1}]").status(s).requestedByName("Cook " + i).build());
        }
        requests.flush();
    }

    @Test
    void theStoreSeesEveryRequestPagedWithCounts() {
        as(Role.INVENTORY, AccessLevel.EDIT);
        seedRequests();
        PageWithCounts<InventoryViews.RequestView> p0 = lists.requests(null, page(null, null, 0, 25));
        PageWithCounts<InventoryViews.RequestView> p1 = lists.requests(null, page(null, null, 1, 25));
        assertThat(p0.totalElements()).isEqualTo(40);
        assertThat(p0.content()).hasSize(25);
        assertThat(p1.content()).hasSize(15);
        assertThat(p0.counts()).containsEntry("all", 40L).containsEntry("OPEN", 15L).containsEntry("ISSUED", 25L);
        assertThat(p1.counts()).isEqualTo(p0.counts());
        assertThat(lists.requests(null, page(null, "open", 0, 25)).totalElements()).isEqualTo(15);
        // The item's name is in the request's lines.
        assertThat(lists.requests(null, page("saffron", null, 0, 25)).totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> lists.requests(null, page(null, null, 0, 101))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> lists.requests(null, page(null, "LOST", 0, 25))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aTeamSeesOnlyItsOwnRequests() {
        as(Role.BAR, AccessLevel.EDIT);
        seedRequests();
        PageWithCounts<InventoryViews.RequestView> bar = lists.requests(null, page(null, null, 0, 25));
        assertThat(bar.totalElements()).isEqualTo(20);
        assertThat(bar.counts()).containsEntry("all", 20L);
        assertThatThrownBy(() -> lists.requests("KITCHEN", page(null, null, 0, 25))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ordersFindTheSupplierByNameAndDeliveriesAddUpTheirValue() {
        as(Role.INVENTORY, AccessLevel.EDIT);
        Supplier fresh = suppliers.saveAndFlush(Supplier.builder().id("s1").businessId("biz-1").hotelId(HOTEL).name("FreshFarm Ltd").build());
        suppliers.saveAndFlush(Supplier.builder().id("s2").businessId("biz-1").hotelId(HOTEL).name("Other Co").build());
        when(inventory.supplierMap()).thenReturn(Map.of("s1", fresh));
        String sentId = null;
        for (int i = 0; i < 30; i++) {
            PurchaseOrder po = orders.save(PurchaseOrder.builder().businessId("biz-1").hotelId(HOTEL).number("PO-" + (1000 + i))
                    .supplierId(i < 4 ? "s1" : "s2").lines("[]").total(new BigDecimal("500.00"))
                    .status(i < 3 ? PurchaseOrder.Status.SENT : PurchaseOrder.Status.RECEIVED).build());
            if (i == 0) {
                sentId = po.getId();
            }
        }
        orders.flush();
        PageWithCounts<InventoryViews.OrderView> fromFresh = lists.purchaseOrders(null, page("freshfarm", null, 0, 25));
        assertThat(fromFresh.totalElements()).isEqualTo(4);
        assertThat(lists.purchaseOrders(null, page(null, "SENT,PARTIAL", 0, 25)).totalElements()).isEqualTo(3);
        assertThat(lists.purchaseOrders(null, page(null, null, 1, 25)).content()).hasSize(5);
        assertThat(lists.purchaseOrders(null, page(null, null, 0, 25)).counts()).containsEntry("SENT", 3L).containsEntry("RECEIVED", 27L);

        for (int i = 0; i < 27; i++) {
            deliveries.save(Delivery.builder().businessId("biz-1").hotelId(HOTEL).storeId("main").lines("[]").total(new BigDecimal("100.00"))
                    .purchaseOrderId(i == 0 ? sentId : null).supplierName(i % 3 == 0 ? "FreshFarm Ltd" : "Walk-in").build());
        }
        deliveries.flush();
        PageWithCounts<InventoryViews.DeliveryView> all = lists.deliveries(null, null, page(null, null, 1, 25));
        assertThat(all.totalElements()).isEqualTo(27);
        assertThat(all.content()).hasSize(2);
        assertThat(all.totals().get("value")).isEqualByComparingTo("2700");
        assertThat(lists.deliveries(null, null, page("PO-1000", null, 0, 25)).totalElements()).isEqualTo(1);
        assertThat(lists.deliveries(null, null, page("freshfarm", null, 0, 25)).totals().get("value")).isEqualByComparingTo("900");
    }

    @Autowired
    com.hospio.inventory.repository.StockItemRepository items;
    @Autowired
    com.hospio.inventory.repository.StockMovementRepository movements;

    @Test
    void movementsAreFoundByTheItemsName() {
        as(Role.INVENTORY, AccessLevel.FULL);
        var saffron = items.save(com.hospio.inventory.entity.StockItem.builder().businessId("biz-1").hotelId(HOTEL).name("Saffron threads").build());
        var rice = items.save(com.hospio.inventory.entity.StockItem.builder().businessId("biz-1").hotelId(HOTEL).name("Rice").build());
        for (int i = 0; i < 30; i++) {
            movements.save(com.hospio.inventory.entity.StockMovement.builder().businessId("biz-1").hotelId(HOTEL).storeId("main")
                    .itemId(i == 3 ? saffron.getId() : rice.getId()).kind(com.hospio.inventory.entity.StockMovement.Kind.RECEIVED)
                    .quantity(BigDecimal.ONE).build());
        }
        movements.flush();
        assertThat(lists.movements(null, null, null, page(null, null, 0, 25)).totalElements()).isEqualTo(30);
        assertThat(lists.movements(null, null, null, page("SAFFRON", null, 0, 25)).totalElements()).isEqualTo(1);
        assertThat(lists.movements(null, null, null, page("rice", null, 1, 25)).content()).hasSize(4);
    }
}
