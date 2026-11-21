package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.hospio.common.kafka.events.DeliveryReceivedEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.dto.InventoryRequests;
import com.hospio.inventory.dto.InventoryViews;
import com.hospio.inventory.entity.Delivery;
import com.hospio.inventory.entity.PurchaseOrder;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.entity.Supplier;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Receiving a delivery tells Finance what to bill: the good units times their cost (damaged ones are not stocked or billed), the
 * supplier and its terms, the order and the invoice; the receive answer says the bill's amount and due date.
 */
class DeliveryBillEventTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final StockItemRepository items = mock(StockItemRepository.class);
    private final StockLevelRepository levels = mock(StockLevelRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final InventoryService service = new InventoryService(stores, items, levels, mock(StockMovementRepository.class),
            mock(StockRequestRepository.class), suppliers, orders, deliveries, mock(StockCountRepository.class), json, events,
            com.hospio.inventory.client.ApprovalLimit.fixed(new BigDecimal("200000")), mock(com.hospio.common.delivery.DeliveryService.class));

    private final Supplier cleanPro = Supplier.builder().name("QA Supplies Ltd").paymentTerms("14 days").build();

    @BeforeEach
    void setUp() {
        HospioUserDetails u = new HospioUserDetails("u1", "jide@x.ng", "Jide Store", "b1", List.of("h1"), Map.of(Role.INVENTORY, AccessLevel.EDIT),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
        Store main = Store.builder().name("Main store").kind("MAIN").build();
        main.setId("main");
        when(stores.findById("main")).thenReturn(Optional.of(main));
        when(stores.findByHotelIdOrderByName("h1")).thenReturn(List.of(main));
        StockItem soap = item("soap", "Liquid soap", "2500");
        StockItem bleach = item("bleach", "Bleach", "1000");
        when(items.findByHotelIdOrderByCategoryAscNameAsc("h1")).thenReturn(List.of(soap, bleach));
        when(levels.lockLevel(any(), any(), any(), eq("main"))).thenAnswer(i -> StockLevel.builder().itemId(i.getArgument(2)).storeId("main")
                .quantity(BigDecimal.ZERO).build());
        when(levels.findByItemId(any())).thenReturn(List.of());
        cleanPro.setId("sup1");
        when(suppliers.findById("sup1")).thenReturn(Optional.of(cleanPro));
        when(suppliers.findByHotelIdOrderByName("h1")).thenReturn(List.of(cleanPro));
        when(deliveries.save(any())).thenAnswer(i -> {
            Delivery d = i.getArgument(0);
            d.setId("del1");
            return d;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private StockItem item(String id, String name, String cost) {
        StockItem i = StockItem.builder().name(name).unit("pcs").unitCost(new BigDecimal(cost)).minLevel(BigDecimal.ZERO).build();
        i.setId(id);
        when(items.findById(id)).thenReturn(Optional.of(i));
        return i;
    }

    private DeliveryReceivedEvent published() {
        ArgumentCaptor<DeliveryReceivedEvent> e = ArgumentCaptor.forClass(DeliveryReceivedEvent.class);
        verify(events).publish(eq(KafkaTopics.DELIVERY_RECEIVED), e.capture());
        return e.getValue();
    }

    @Test
    void againstAnOrderTheBillIsTheGoodUnitsTimesCostDueByTheSuppliersTerms() throws Exception {
        PurchaseOrder po = PurchaseOrder.builder().number("PO-2026-00007").supplierId("sup1").storeId("main").status(PurchaseOrder.Status.SENT)
                .lines(json.writeValueAsString(List.of(
                        new InventoryViews.OrderLine("soap", "Liquid soap", "pcs", new BigDecimal("4"), new BigDecimal("2500"), BigDecimal.ZERO, BigDecimal.ZERO),
                        new InventoryViews.OrderLine("bleach", "Bleach", "pcs", new BigDecimal("3"), new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO))))
                .receivedValue(BigDecimal.ZERO).build();
        po.setId("po1");
        when(orders.lockById("po1")).thenReturn(Optional.of(po));

        // 4 soap good; bleach: 2 good and 1 damaged (noted, not stocked, not billed).
        InventoryViews.DeliveryView view = service.receive(new InventoryRequests.DeliveryRequest("po1", null, null, "INV-77", List.of(
                new InventoryRequests.Line("soap", null, new BigDecimal("4"), null, null, null),
                new InventoryRequests.Line("bleach", null, new BigDecimal("2"), null, new BigDecimal("1"), "1 bottle leaking")), null));

        DeliveryReceivedEvent e = published();
        assertThat(e.getTotal()).isEqualByComparingTo("12000"); // 4 × 2,500 + 2 × 1,000; the damaged bleach is not in it
        assertThat(e.getDeliveryId()).isEqualTo("del1");
        assertThat(e.getPurchaseOrderId()).isEqualTo("po1");
        assertThat(e.getPurchaseOrderNumber()).isEqualTo("PO-2026-00007");
        assertThat(e.getSupplierId()).isEqualTo("sup1");
        assertThat(e.getSupplierName()).isEqualTo("QA Supplies Ltd");
        assertThat(e.getInvoiceNumber()).isEqualTo("INV-77");
        assertThat(e.getTermsDays()).isEqualTo(14);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        assertThat(e.getReceivedOn()).isEqualTo(today.toString());
        assertThat(view.billAmount()).isEqualByComparingTo("12000");
        assertThat(view.billDueOn()).isEqualTo(today.plusDays(14));
    }

    @Test
    void withoutAnOrderTheSupplierIsFoundByNameAndNoTermsMeansDueToday() {
        cleanPro.setPaymentTerms("Pay on delivery");
        InventoryViews.DeliveryView view = service.receive(new InventoryRequests.DeliveryRequest(null, "qa supplies ltd", "main", null, List.of(
                new InventoryRequests.Line("soap", null, new BigDecimal("2"), new BigDecimal("2600"), BigDecimal.ZERO, null)), null));

        DeliveryReceivedEvent e = published();
        assertThat(e.getSupplierId()).isEqualTo("sup1");
        assertThat(e.getPurchaseOrderId()).isNull();
        assertThat(e.getTotal()).isEqualByComparingTo("5200");
        assertThat(e.getTermsDays()).isZero();
        assertThat(view.billDueOn()).isEqualTo(LocalDate.now(ZoneId.of("Africa/Lagos")));
    }

    @Test
    void aDeliveryOfOnlyDamagedGoodsHasNoBill() {
        InventoryViews.DeliveryView view = service.receive(new InventoryRequests.DeliveryRequest(null, "QA Supplies Ltd", "main", null, List.of(
                new InventoryRequests.Line("soap", null, BigDecimal.ZERO, null, new BigDecimal("3"), null)), null));

        assertThat(published().getTotal()).isEqualByComparingTo("0");
        assertThat(view.billAmount()).isNull();
        verify(events, never()).publish(eq(KafkaTopics.STOCK_LOW), any());
    }
}
