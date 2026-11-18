package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.kafka.EventPublisher;
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
import org.junit.jupiter.api.Test;

/** A new hotel starts with a Main, Kitchen, Bar and Housekeeping store, once, and never on top of stores it already has (DEF-077). */
class DefaultStoresTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final InventoryService service = new InventoryService(stores, mock(StockItemRepository.class), mock(StockLevelRepository.class),
            mock(StockMovementRepository.class), mock(StockRequestRepository.class), mock(SupplierRepository.class),
            mock(PurchaseOrderRepository.class), mock(DeliveryRepository.class), mock(StockCountRepository.class), new ObjectMapper(),
            mock(EventPublisher.class), com.hospio.inventory.client.ApprovalLimit.fixed(new BigDecimal("200000")),
            mock(com.hospio.common.delivery.DeliveryService.class));

    @Test
    void aHotelWithNoStoresGetsTheFourDefaults() {
        List<Store> saved = new ArrayList<>();
        when(stores.findByHotelIdOrderByName("h1")).thenReturn(List.of());
        when(stores.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        assertThat(service.createDefaultStores("h1")).isEqualTo(4);

        assertThat(saved).extracting(Store::getName).containsExactly("Main store", "Kitchen store", "Bar store", "Housekeeping store");
        assertThat(saved).extracting(Store::getKind).containsExactly("MAIN", "KITCHEN", "BAR", "HOUSEKEEPING");
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.getHotelId()).isEqualTo("h1");
            assertThat(s.isActive()).isTrue();
        });
    }

    @Test
    void aHotelThatAlreadyHasAStoreEvenAnInactiveOneIsLeftAlone() {
        Store old = Store.builder().name("Old store").kind("MAIN").active(false).build();
        when(stores.findByHotelIdOrderByName("h1")).thenReturn(List.of(old));

        assertThat(service.createDefaultStores("h1")).isZero();
        verify(stores, never()).save(any());
    }
}
