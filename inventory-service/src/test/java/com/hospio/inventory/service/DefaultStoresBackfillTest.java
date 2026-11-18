package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.client.TenantHotels;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Existing hotels with no store get the four defaults through the listener's code path, under their own tenant, once. */
class DefaultStoresBackfillTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final InventoryService service = new InventoryService(stores, mock(StockItemRepository.class), mock(StockLevelRepository.class),
            mock(StockMovementRepository.class), mock(StockRequestRepository.class), mock(SupplierRepository.class),
            mock(PurchaseOrderRepository.class), mock(DeliveryRepository.class), mock(StockCountRepository.class), new ObjectMapper(),
            mock(EventPublisher.class), com.hospio.inventory.client.ApprovalLimit.fixed(new BigDecimal("200000")),
            mock(com.hospio.common.delivery.DeliveryService.class));

    @Test
    void hotelsWithoutStoresGetTheDefaultsAndASecondRunChangesNothing() {
        Map<String, List<Store>> byHotel = new HashMap<>();
        byHotel.put("h-has", new ArrayList<>(List.of(Store.builder().hotelId("h-has").name("Pantry").kind("OTHER").build())));
        List<String> boundAs = new ArrayList<>();
        when(stores.findByHotelIdOrderByName(anyString())).thenAnswer(i -> List.copyOf(byHotel.getOrDefault(i.getArgument(0, String.class), List.of())));
        when(stores.save(any(Store.class))).thenAnswer(i -> {
            Store s = i.getArgument(0);
            boundAs.add(TenantContext.require().businessId() + "/" + TenantContext.require().hotelId());
            byHotel.computeIfAbsent(s.getHotelId(), k -> new ArrayList<>()).add(s);
            return s;
        });
        TenantHotels tenant = mock(TenantHotels.class);
        when(tenant.all()).thenReturn(List.of(new TenantHotels.HotelRef("h-new", "b1", "Eko Suites"),
                new TenantHotels.HotelRef("h-has", "b1", "Eko Annex"), new TenantHotels.HotelRef("h-other", "b2", "Ikeja Inn")));
        DefaultStoresBackfill backfill = new DefaultStoresBackfill(tenant, service);

        DefaultStoresBackfill.Result first = backfill.run();

        assertThat(first.hotelsChecked()).isEqualTo(3);
        assertThat(first.hotelsChanged()).isEqualTo(2);
        assertThat(first.storesCreated()).isEqualTo(8);
        assertThat(first.failed()).isEmpty();
        assertThat(byHotel.get("h-new")).extracting(Store::getKind).containsExactly("MAIN", "KITCHEN", "BAR", "HOUSEKEEPING");
        assertThat(byHotel.get("h-has")).hasSize(1);
        assertThat(boundAs).containsOnly("b1/h-new", "b2/h-other");
        assertThat(TenantContext.current()).isEmpty();

        DefaultStoresBackfill.Result second = backfill.run();

        assertThat(second.hotelsChecked()).isEqualTo(3);
        assertThat(second.hotelsChanged()).isZero();
        assertThat(second.storesCreated()).isZero();
        assertThat(byHotel.get("h-new")).hasSize(4);
    }
}
