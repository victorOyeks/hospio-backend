package com.hospio.fnb.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.ItemSoldOutEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.fnb.dto.HappyHourRequest;
import com.hospio.fnb.dto.MenuItemRequest;
import com.hospio.fnb.dto.OutletRequest;
import com.hospio.fnb.dto.PriceChangeRequestDto;
import com.hospio.fnb.dto.TableRequest;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.HappyHour;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.PriceChangeRequest;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.PriceChangeRequestRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outlets, tables and menus. A cook or bartender can turn an item off (sold out); changing a price
 * or the menu needs Full access, otherwise the change goes to a manager for approval.
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final OutletRepository outlets;
    private final DiningTableRepository tables;
    private final MenuItemRepository items;
    private final PriceChangeRequestRepository priceChanges;
    private final HappyHourRepository happyHours;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<Outlet> outlets() {
        return outlets.findByHotelIdOrderByName(TenantContext.requireHotel());
    }

    @Transactional
    public Outlet saveOutlet(String id, OutletRequest request) {
        Outlet o = id == null ? new Outlet() : outlet(id);
        o.setName(request.name());
        o.setKind(request.kind());
        return outlets.save(o);
    }

    @Transactional(readOnly = true)
    public List<DiningTable> tables(String outletId) {
        return tables.findByOutletIdOrderByNumber(outletId);
    }

    @Transactional
    public DiningTable saveTable(String outletId, String id, TableRequest request) {
        outlet(outletId);
        DiningTable t = id == null ? DiningTable.builder().outletId(outletId).build()
                : tables.findById(id).orElseThrow(() -> new ResourceNotFoundException("Table not found"));
        t.setNumber(request.number());
        if (request.seats() != null) {
            t.setSeats(request.seats());
        }
        if (request.active() != null) {
            t.setActive(request.active());
        }
        return tables.save(t);
    }

    @Transactional(readOnly = true)
    public List<MenuItem> menu(String outletId) {
        return items.findByOutletIdAndActiveTrueOrderByCategoryAscSortOrderAscNameAsc(outletId);
    }

    @Transactional
    public MenuItem saveItem(String outletId, String id, MenuItemRequest request) {
        requireFull();
        outlet(outletId);
        MenuItem m = id == null ? MenuItem.builder().outletId(outletId).build() : item(id);
        m.setCategory(request.category());
        m.setName(request.name());
        m.setDescription(request.description());
        m.setPrice(Money.of(request.price()));
        m.setTaxRate(request.taxRate());
        m.setOptions(request.options());
        if (request.sortOrder() != null) {
            m.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            m.setActive(request.active());
        }
        return items.save(m);
    }

    /** Sold out / back on: anyone in the outlet can do this. */
    @Transactional
    public MenuItem setAvailable(String id, boolean available) {
        MenuItem m = item(id);
        m.setAvailable(available);
        items.save(m);
        if (!available) {
            events.publish(KafkaTopics.ITEM_SOLD_OUT, ItemSoldOutEvent.builder().itemId(m.getId()).itemName(m.getName())
                    .outletName(outlet(m.getOutletId()).getName()).build());
        }
        return m;
    }

    /** Full access changes the price now; anyone else sends it to a manager. */
    @Transactional
    public MenuItem changePrice(String id, PriceChangeRequestDto request) {
        HospioUserDetails user = CurrentUser.require();
        MenuItem m = item(id);
        if (!Money.isPositive(request.newPrice())) {
            throw new BadRequestException("Enter the new price");
        }
        if (hasFull(user)) {
            m.setPrice(Money.of(request.newPrice()));
            return items.save(m);
        }
        String approvalId = UUID.randomUUID().toString();
        priceChanges.save(PriceChangeRequest.builder().menuItemId(m.getId()).oldPrice(m.getPrice()).newPrice(Money.of(request.newPrice()))
                .approvalId(approvalId).requestedBy(user.getUserId()).build());
        events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(approvalId).sourceModule("FNB")
                .sourceRef(m.getId()).kind("PRICE_CHANGE").amount(Money.of(request.newPrice())).requesterId(user.getUserId())
                .requesterName(user.getFullName()).approverLevel("MANAGER")
                .description("Change " + m.getName() + " from " + m.getPrice() + " to " + Money.of(request.newPrice())
                        + (request.reason() == null ? "" : ": " + request.reason())).build());
        throw new BadRequestException("Price change sent to the manager for approval");
    }

    @Transactional
    public void applyApprovedPriceChange(String approvalId, String decision) {
        PriceChangeRequest pc = priceChanges.findByApprovalId(approvalId).orElse(null);
        if (pc == null || !"PENDING".equals(pc.getStatus())) {
            return;
        }
        pc.setStatus(decision);
        priceChanges.save(pc);
        if ("APPROVED".equals(decision)) {
            MenuItem m = item(pc.getMenuItemId());
            m.setPrice(pc.getNewPrice());
            items.save(m);
        }
    }

    @Transactional(readOnly = true)
    public List<HappyHour> happyHours(String outletId) {
        return happyHours.findByOutletIdAndActiveTrue(outletId);
    }

    @Transactional
    public HappyHour saveHappyHour(String outletId, String id, HappyHourRequest request) {
        requireFull();
        outlet(outletId);
        HappyHour h = id == null ? HappyHour.builder().outletId(outletId).build()
                : happyHours.findById(id).orElseThrow(() -> new ResourceNotFoundException("Happy hour not found"));
        h.setName(request.name());
        h.setFromTime(request.fromTime());
        h.setToTime(request.toTime());
        h.setDays(request.days());
        h.setDiscountPct(request.discountPct());
        if (request.active() != null) {
            h.setActive(request.active());
        }
        return happyHours.save(h);
    }

    Outlet outlet(String id) {
        return outlets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }

    MenuItem item(String id) {
        return items.findById(id).orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }

    private static boolean hasFull(HospioUserDetails user) {
        return user.isManagerOrOwner() || user.has(Role.KITCHEN, AccessLevel.FULL) || user.has(Role.BAR, AccessLevel.FULL);
    }

    private static void requireFull() {
        if (!hasFull(CurrentUser.require())) {
            throw new ForbiddenException("Managing the menu needs Full access (head chef or bar lead)");
        }
    }
}
