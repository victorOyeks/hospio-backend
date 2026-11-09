package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.OrderSettledEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.entity.StockMovement;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.repository.DeliveryRepository;
import com.hospio.inventory.repository.PurchaseOrderRepository;
import com.hospio.inventory.repository.StockCountRepository;
import com.hospio.inventory.repository.StockItemRepository;
import com.hospio.inventory.repository.StockLevelRepository;
import com.hospio.inventory.repository.StockMovementRepository;
import com.hospio.inventory.repository.StockRequestRepository;
import com.hospio.inventory.repository.StoreRepository;
import com.hospio.inventory.repository.SupplierRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Selling a dish draws its recipe from the outlet's store once per order, never below zero; only a store's own team may use its stock. */
class OrderConsumptionTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final StockItemRepository items = mock(StockItemRepository.class);
    private final StockLevelRepository levels = mock(StockLevelRepository.class);
    private final StockMovementRepository movements = mock(StockMovementRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final InventoryService service = new InventoryService(stores, items, levels, movements, mock(StockRequestRepository.class), mock(SupplierRepository.class),
            mock(PurchaseOrderRepository.class), mock(DeliveryRepository.class), mock(StockCountRepository.class), new ObjectMapper(), events);

    private final List<StockMovement> recorded = new ArrayList<>();
    private final StockLevel rice = StockLevel.builder().itemId("rice").storeId("kitchen").quantity(new BigDecimal("10.00")).build();
    private final StockLevel chicken = StockLevel.builder().itemId("chicken").storeId("kitchen").quantity(new BigDecimal("0.30")).build();

    @BeforeEach
    void setUp() {
        Store store = Store.builder().name("Kitchen store").kind("KITCHEN").build();
        store.setId("kitchen");
        when(stores.findById("kitchen")).thenReturn(Optional.of(store));
        item("rice", "Rice", "2");
        item("chicken", "Chicken", "1");
        when(levels.findByItemIdAndStoreId("rice", "kitchen")).thenReturn(Optional.of(rice));
        when(levels.findByItemIdAndStoreId("chicken", "kitchen")).thenReturn(Optional.of(chicken));
        when(levels.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(movements.save(any())).thenAnswer(inv -> {
            recorded.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(movements.existsByReferenceAndKind(anyString(), eq(StockMovement.Kind.USED))).thenAnswer(inv -> !recorded.isEmpty());
    }

    private void item(String id, String name, String min) {
        StockItem i = StockItem.builder().name(name).unit("kg").minLevel(new BigDecimal(min)).build();
        i.setId(id);
        when(items.findById(id)).thenReturn(Optional.of(i));
    }

    private static OrderSettledEvent order(OrderSettledEvent.Line... lines) {
        return OrderSettledEvent.builder().orderId("o1").orderNumber("K-0001").outletId("restaurant").storeId("kitchen").lines(List.of(lines)).build();
    }

    private static OrderSettledEvent.Line dish(String name, int qty, Object... recipe) {
        List<OrderSettledEvent.Ingredient> r = new ArrayList<>();
        for (int i = 0; i < recipe.length; i += 2) {
            r.add(new OrderSettledEvent.Ingredient((String) recipe[i], new BigDecimal(recipe[i + 1].toString())));
        }
        return new OrderSettledEvent.Line("m-" + name, name, qty, r);
    }

    @Test
    void drawsEachRecipeLineTimesTheQuantityAsUsedMovements() {
        service.onOrderSettled(order(dish("Jollof", 2, "rice", "0.2"), dish("Fried rice", 1, "rice", "0.2")));

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getKind()).isEqualTo(StockMovement.Kind.USED);
        assertThat(recorded.get(0).getReference()).isEqualTo("ORDER-o1");
        assertThat(recorded.get(0).getQuantity()).isEqualByComparingTo("-0.60");
        assertThat(rice.getQuantity()).isEqualByComparingTo("9.40");
    }

    @Test
    void anOrderSettledTwiceDrawsOnce() {
        OrderSettledEvent e = order(dish("Jollof", 1, "rice", "0.2"));

        service.onOrderSettled(e);
        service.onOrderSettled(e);

        assertThat(recorded).hasSize(1);
        assertThat(rice.getQuantity()).isEqualByComparingTo("9.80");
    }

    @Test
    void itemsWithoutARecipeDrawNothingAndStockNeverGoesBelowZero() {
        service.onOrderSettled(order(dish("Water", 3)));
        assertThat(recorded).isEmpty();

        service.onOrderSettled(order(dish("Chicken wings", 2, "chicken", "0.25")));
        assertThat(chicken.getQuantity()).isEqualByComparingTo("0.00");
        assertThat(recorded.get(0).getQuantity()).isEqualByComparingTo("-0.30");
        assertThat(recorded.get(0).getNote()).contains("short by 0.20");
    }

    @Test
    void raisesStockLowWhenTheLevelFallsToItsMinimum() {
        rice.setQuantity(new BigDecimal("2.30"));

        service.onOrderSettled(order(dish("Jollof", 1, "rice", "0.4")));

        verify(events).publish(eq(KafkaTopics.STOCK_LOW), any());
    }

    @Test
    void anOutletWithNoStoreOrAnUnknownStoreDrawsNothing() {
        service.onOrderSettled(OrderSettledEvent.builder().orderId("o1").lines(List.of(dish("Jollof", 1, "rice", "0.2"))).build());
        service.onOrderSettled(OrderSettledEvent.builder().orderId("o1").storeId("elsewhere").lines(List.of(dish("Jollof", 1, "rice", "0.2"))).build());

        assertThat(recorded).isEmpty();
        verify(events, never()).publish(any(), any());
    }

    @Test
    void onlyTheStoresOwnTeamTheInventoryTeamOrAManagerMayUseItsStock() {
        Store kitchen = stores.findById("kitchen").orElseThrow();
        assertThat(InventoryService.canUse(user(Role.KITCHEN, AccessLevel.EDIT), kitchen)).isTrue();
        assertThat(InventoryService.canUse(user(Role.INVENTORY, AccessLevel.EDIT), kitchen)).isTrue();
        assertThat(InventoryService.canUse(user(Role.MANAGER, AccessLevel.FULL), kitchen)).isTrue();
        assertThat(InventoryService.canUse(user(Role.BAR, AccessLevel.EDIT), kitchen)).isFalse();
        assertThat(InventoryService.canUse(user(Role.KITCHEN, AccessLevel.VIEW), kitchen)).isFalse();
    }

    private static HospioUserDetails user(Role role, AccessLevel level) {
        return new HospioUserDetails("u1", "x@x.ng", "Tester", "b1", List.of("h1"), Map.of(role, level), JwtUtil.SCOPE_HOTEL);
    }
}
