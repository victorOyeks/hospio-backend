package com.hospio.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.dto.InventoryRequests;
import com.hospio.inventory.dto.InventoryViews;
import com.hospio.inventory.entity.PurchaseOrder;
import com.hospio.inventory.entity.StockCount;
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
 * Samuel's inventory QA run (22 Nov 2026): the manager's bell when a count waits (DEF-100), the counter's bell when it is approved
 * (DEF-101), the contact person's phone (DEF-097), the order count behind the On order tile (DEF-106), the reorder-up-to level
 * (DEF-107) and stopping an item that still holds stock (DEF-108).
 */
class InventoryQaFixesTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final StockItemRepository items = mock(StockItemRepository.class);
    private final StockLevelRepository levels = mock(StockLevelRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final StockCountRepository counts = mock(StockCountRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final InventoryService service = new InventoryService(stores, items, levels, mock(StockMovementRepository.class),
            mock(StockRequestRepository.class), suppliers, orders, mock(DeliveryRepository.class), counts, json, events,
            com.hospio.inventory.client.ApprovalLimit.fixed(new BigDecimal("200000")), mock(com.hospio.common.delivery.DeliveryService.class));

    private final Store main = Store.builder().name("Main store").kind("MAIN").build();
    private StockItem rice;

    @BeforeEach
    void setUp() {
        signIn("jide", "Jide Alabi", Role.INVENTORY);
        main.setId("main");
        when(stores.findById("main")).thenReturn(Optional.of(main));
        when(stores.findByHotelIdOrderByName("h1")).thenReturn(List.of(main));
        rice = StockItem.builder().name("Rice").unit("kg").unitCost(new BigDecimal("1200")).minLevel(BigDecimal.ZERO).build();
        rice.setId("rice");
        when(items.findById("rice")).thenReturn(Optional.of(rice));
        when(items.findByHotelIdOrderByCategoryAscNameAsc("h1")).thenReturn(List.of(rice));
        when(items.save(any())).thenAnswer(i -> i.getArgument(0));
        when(counts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(suppliers.save(any())).thenAnswer(i -> {
            Supplier s = i.getArgument(0);
            s.setId("sup1");
            return s;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void signIn(String id, String name, Role role) {
        HospioUserDetails u = new HospioUserDetails(id, id + "@x.ng", name, "b1", List.of("h1"), Map.of(role, AccessLevel.FULL), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    private NoticeEvent notice() {
        ArgumentCaptor<NoticeEvent> e = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), e.capture());
        return e.getValue();
    }

    private StockCount count(StockCount.Status status) {
        StockCount c = StockCount.builder().storeId("main").status(status).startedBy("jide").startedByName("Jide Alabi").build();
        c.setId("cnt1");
        when(counts.findById("cnt1")).thenReturn(Optional.of(c));
        when(counts.lockById("cnt1")).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void submittingACountTellsTheManagersWithALinkToIt() {
        count(StockCount.Status.OPEN);
        when(levels.findByItemIdAndStoreId("rice", "main")).thenReturn(Optional.of(StockLevel.builder().itemId("rice").storeId("main")
                .quantity(new BigDecimal("12")).build()));

        service.submitCount("cnt1", new InventoryRequests.CountLinesRequest(List.of(
                new InventoryRequests.Line("rice", null, new BigDecimal("10"), null)), null));

        NoticeEvent n = notice();
        assertThat(n.getRoles()).containsExactly("MANAGER");
        assertThat(n.getTitle()).isEqualTo("Stock count to approve · Main store");
        assertThat(n.getBody()).isEqualTo("Jide Alabi counted the Main store · missing worth ₦2,400");
        assertThat(n.getLink()).isEqualTo("/inventory?screen=counts&open=cnt1");
    }

    @Test
    void approvingACountTellsWhoCountedIt() throws Exception {
        StockCount c = count(StockCount.Status.COUNTED);
        c.setCountedBy("jide");
        c.setCountedByName("Jide Alabi");
        c.setVarianceValue(BigDecimal.ZERO);
        c.setLines(json.writeValueAsString(List.of(new InventoryViews.CountLine("rice", "Rice", "kg", BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, new BigDecimal("1200"), BigDecimal.ZERO))));
        signIn("tunde", "Tunde Bakare", Role.MANAGER);

        service.approveCount("cnt1");

        NoticeEvent n = notice();
        assertThat(n.getUserIds()).containsExactly("jide");
        assertThat(n.getRoles()).isNull();
        assertThat(n.getTitle()).isEqualTo("Your count was approved · Main store");
        assertThat(n.getBody()).isEqualTo("Tunde Bakare approved it · levels corrected (no difference)");
        assertThat(n.getLink()).isEqualTo("/counts?open=cnt1");
    }

    @Test
    void aManagerApprovingTheirOwnCountIsNotToldAboutIt() throws Exception {
        StockCount c = count(StockCount.Status.COUNTED);
        c.setCountedBy("tunde");
        c.setLines(json.writeValueAsString(List.of()));
        signIn("tunde", "Tunde Bakare", Role.MANAGER);

        service.approveCount("cnt1");

        verify(events, never()).publish(eq(KafkaTopics.NOTICE), any());
    }

    @Test
    void theCountDifferenceReadsInNaira() {
        assertThat(InventoryService.countDifference(new BigDecimal("-2400"))).isEqualTo("missing worth ₦2,400");
        assertThat(InventoryService.countDifference(new BigDecimal("600"))).isEqualTo("extra worth ₦600");
        assertThat(InventoryService.countDifference(null)).isEqualTo("no difference");
    }

    private InventoryRequests.SupplierRequest supplier(String contactName, String contactPhone) {
        return new InventoryRequests.SupplierRequest("QA Contact Co", contactName, "0803 555 0778", null, contactPhone, "7 days", null, null,
                null, List.of());
    }

    @Test
    void aContactPhoneLike0000000IsRefusedLikeTheCompanyPhone() {
        assertThatThrownBy(() -> service.saveSupplier(null, supplier("Bola", "0000000")))
                .isInstanceOf(BadRequestException.class).hasMessage("Enter a phone number like 0803 123 4567 for the contact person");
        assertThatThrownBy(() -> service.saveSupplier(null, supplier("Bola", "0000000000")))
                .isInstanceOf(BadRequestException.class).hasMessage("Enter a phone number like 0803 123 4567 for the contact person");
        verify(suppliers, never()).save(any());
    }

    @Test
    void aNumberTypedAsTheContactsNameIsRefused() {
        assertThatThrownBy(() -> service.saveSupplier(null, supplier("0000000", null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Enter the contact person's name · their number goes under Contact phone");
    }

    @Test
    void theContactPhoneIsOptionalAndKeptWhenValid() {
        when(suppliers.findByHotelIdOrderByName("h1")).thenReturn(List.of());
        ArgumentCaptor<Supplier> saved = ArgumentCaptor.forClass(Supplier.class);
        try {
            service.saveSupplier(null, supplier("Bola", "0803 123 4567"));
        } catch (RuntimeException ignored) {
            // the answer is re-read from the list, which this mock leaves empty
        }
        verify(suppliers).save(saved.capture());
        assertThat(saved.getValue().getContactPhone()).isEqualTo("0803 123 4567");
        assertThat(saved.getValue().getContactName()).isEqualTo("Bola");
    }

    @Test
    void theValueReportCountsTheOrdersStillToArrive() throws Exception {
        PurchaseOrder sent = PurchaseOrder.builder().number("PO-1").status(PurchaseOrder.Status.SENT).lines(json.writeValueAsString(List.of(
                new InventoryViews.OrderLine("rice", "Rice", "kg", new BigDecimal("10"), new BigDecimal("1800"), new BigDecimal("4"), BigDecimal.ZERO))))
                .build();
        PurchaseOrder part = PurchaseOrder.builder().number("PO-2").status(PurchaseOrder.Status.PARTIAL).lines(json.writeValueAsString(List.of(
                new InventoryViews.OrderLine("rice", "Rice", "kg", new BigDecimal("2"), new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO))))
                .build();
        when(orders.findByHotelIdAndStatusIn(eq("h1"), anyCollection())).thenReturn(List.of(sent, part));

        InventoryViews.ValueReport v = service.value();

        assertThat(v.onOrder()).isEqualByComparingTo("12800"); // 6 × 1,800 still to come + 2 × 1,000
        assertThat(v.ordersOnTheWay()).isEqualTo(2);
    }

    @Test
    void theReorderUpToLevelCantBeBelowTheMinimum() {
        assertThatThrownBy(() -> service.saveItem("rice", new InventoryRequests.ItemRequest("Rice", null, "kg", BigDecimal.TEN,
                new BigDecimal("5"), null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("The reorder-up-to level can't be below the minimum (10)");
    }

    @Test
    void anItemStillHoldingStockCantBeStopped() {
        when(levels.findByItemId("rice")).thenReturn(List.of(StockLevel.builder().itemId("rice").storeId("main").quantity(new BigDecimal("12")).build()));

        assertThatThrownBy(() -> service.saveItem("rice", new InventoryRequests.ItemRequest("Rice", null, "kg", null, null, null, null, false,
                null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Rice still has 12 kg in Main store · count it out or move it first");
        assertThat(rice.isActive()).isTrue();
    }

    @Test
    void anEmptyItemCanBeStopped() {
        when(levels.findByItemId("rice")).thenReturn(List.of(StockLevel.builder().itemId("rice").storeId("main").quantity(BigDecimal.ZERO).build()));

        service.saveItem("rice", new InventoryRequests.ItemRequest("Rice", null, "kg", null, null, null, null, false, null, null));

        assertThat(rice.isActive()).isFalse();
    }
}
