package com.hospio.fnb.service;

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
import com.hospio.fnb.dto.CategoryRequest;
import com.hospio.fnb.dto.HappyHourRequest;
import com.hospio.fnb.dto.MenuItemRequest;
import com.hospio.fnb.dto.OutletRequest;
import com.hospio.fnb.dto.PriceChangeRequestDto;
import com.hospio.fnb.dto.TableRequest;
import com.hospio.fnb.entity.DiningTable;
import com.hospio.fnb.entity.HappyHour;
import com.hospio.fnb.entity.MenuCategory;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.PriceChangeRequest;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuCategoryRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.PriceChangeRequestRepository;
import com.hospio.fnb.rules.OutletAccess;
import com.hospio.fnb.rules.Recipes;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outlets, tables, categories and menus. A cook or bartender can turn an item off (sold out); changing the menu or happy hours needs
 * Full access on the outlet's own role (or a manager); a price change from anyone else goes to a manager for approval.
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final OutletRepository outlets;
    private final DiningTableRepository tables;
    private final MenuItemRepository items;
    private final MenuCategoryRepository categories;
    private final PriceChangeRequestRepository priceChanges;
    private final HappyHourRepository happyHours;
    private final EventPublisher events;

    /** What a price change did: applied now (item) or sent for approval (pending). */
    public record PriceChangeResult(MenuItem item, PriceChangeRequest pending) {
    }

    @Transactional(readOnly = true)
    public List<Outlet> outlets() {
        return outlets.findByHotelIdOrderByName(TenantContext.requireHotel());
    }

    @Transactional
    public Outlet saveOutlet(String id, OutletRequest request) {
        Outlet o = id == null ? new Outlet() : outlet(id);
        o.setName(request.name());
        o.setKind(request.kind());
        if (request.storeId() != null) {
            o.setStoreId(request.storeId().isBlank() ? null : request.storeId());
        }
        if (request.status() != null) {
            o.setStatus(request.status());
        }
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

    /** The menu staff sell from; {@code all} adds inactive items for the manage-menu screen (manager or Full on the outlet's role). */
    @Transactional(readOnly = true)
    public List<MenuItem> menu(String outletId, boolean all) {
        if (all) {
            requireFull(outlet(outletId));
            return items.findByOutletIdOrderByCategoryAscSortOrderAscNameAsc(outletId);
        }
        return items.findByOutletIdAndActiveTrueOrderByCategoryAscSortOrderAscNameAsc(outletId);
    }

    @Transactional(readOnly = true)
    public List<MenuCategory> categories(String outletId) {
        return categories.findByOutletIdOrderBySortOrderAscNameAsc(outletId);
    }

    @Transactional
    public MenuCategory saveCategory(String outletId, String id, CategoryRequest request) {
        requireFull(outlet(outletId));
        String name = request.name().trim();
        MenuCategory existing = categories.findByOutletIdAndName(outletId, name).orElse(null);
        if (existing != null && !existing.getId().equals(id)) {
            throw new BadRequestException("There is already a category called " + name);
        }
        MenuCategory c = id == null ? MenuCategory.builder().outletId(outletId).build()
                : categories.findById(id).filter(x -> x.getOutletId().equals(outletId)).orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        String oldName = c.getName();
        c.setName(name);
        if (request.sortOrder() != null) {
            c.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            c.setActive(request.active());
        }
        categories.save(c);
        if (oldName != null && !oldName.equals(name)) {
            // Menu items keep their category by name, so a rename follows them.
            items.findByOutletIdOrderByCategoryAscSortOrderAscNameAsc(outletId).stream().filter(i -> oldName.equals(i.getCategory())).forEach(i -> {
                i.setCategory(name);
                items.save(i);
            });
        }
        return c;
    }

    @Transactional
    public MenuItem saveItem(String outletId, String id, MenuItemRequest request) {
        Outlet outlet = outlet(outletId);
        requireFull(outlet);
        if (!Money.isPositive(request.price())) {
            throw new BadRequestException("Enter the price");
        }
        MenuItem m = id == null ? MenuItem.builder().outletId(outletId).build() : item(id);
        if (!outletId.equals(m.getOutletId())) {
            throw new ResourceNotFoundException("Menu item not found");
        }
        String category = request.category().trim();
        m.setCategory(category);
        m.setName(request.name());
        m.setDescription(request.description());
        m.setPrice(Money.of(request.price()));
        m.setTaxRate(request.taxRate());
        m.setOptions(request.options());
        if (request.recipe() != null) {
            m.setRecipeJson(Recipes.write(Recipes.parse(request.recipe())));
        }
        if (request.sortOrder() != null) {
            m.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            m.setActive(request.active());
        }
        if (categories.findByOutletIdAndName(outletId, category).isEmpty()) {
            categories.save(MenuCategory.builder().outletId(outletId).name(category).sortOrder(categories.findByOutletIdOrderBySortOrderAscNameAsc(outletId).size()).build());
        }
        return items.save(m);
    }

    /** Sold out / back on: anyone who works the outlet can do this. */
    @Transactional
    public MenuItem setAvailable(String id, boolean available) {
        MenuItem m = item(id);
        Outlet outlet = outlet(m.getOutletId());
        if (!OutletAccess.works(CurrentUser.require(), outlet)) {
            throw new ForbiddenException("This item belongs to the " + outlet.getName());
        }
        m.setAvailable(available);
        items.save(m);
        if (!available) {
            events.publish(KafkaTopics.ITEM_SOLD_OUT, ItemSoldOutEvent.builder().itemId(m.getId()).itemName(m.getName())
                    .outletName(outlet.getName()).build());
        }
        return m;
    }

    /** Full access changes the price now; anyone else who works the outlet sends it to a manager. */
    @Transactional
    public PriceChangeResult changePrice(String id, PriceChangeRequestDto request) {
        HospioUserDetails user = CurrentUser.require();
        MenuItem m = item(id);
        Outlet outlet = outlet(m.getOutletId());
        if (!OutletAccess.works(user, outlet)) {
            throw new ForbiddenException("This item belongs to the " + outlet.getName());
        }
        if (!Money.isPositive(request.newPrice())) {
            throw new BadRequestException("Enter the new price");
        }
        if (OutletAccess.full(user, outlet)) {
            m.setPrice(Money.of(request.newPrice()));
            return new PriceChangeResult(items.save(m), null);
        }
        String approvalId = UUID.randomUUID().toString();
        PriceChangeRequest pc = priceChanges.save(PriceChangeRequest.builder().menuItemId(m.getId()).oldPrice(m.getPrice()).newPrice(Money.of(request.newPrice()))
                .approvalId(approvalId).requestedBy(user.getUserId()).build());
        events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(approvalId).sourceModule("FNB")
                .sourceRef(m.getId()).kind("PRICE_CHANGE").amount(Money.of(request.newPrice())).requesterId(user.getUserId())
                .requesterName(user.getFullName()).approverLevel("MANAGER")
                .description("Change " + m.getName() + " (" + outlet.getName() + ") from " + naira(m.getPrice()) + " to " + naira(request.newPrice())
                        + (request.reason() == null ? "" : ": " + request.reason())).build());
        return new PriceChangeResult(m, pc);
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
        requireFull(outlet(outletId));
        if (request.discountPct().signum() <= 0 || request.discountPct().compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("The discount is a percentage between 0 and 100");
        }
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

    private static String naira(java.math.BigDecimal amount) {
        return "₦" + java.text.NumberFormat.getIntegerInstance(java.util.Locale.UK).format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    Outlet outlet(String id) {
        return outlets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }

    MenuItem item(String id) {
        return items.findById(id).orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }

    private static void requireFull(Outlet outlet) {
        if (!OutletAccess.full(CurrentUser.require(), outlet)) {
            throw new ForbiddenException("Managing the " + outlet.getName() + " menu needs Full access (head chef, bar lead or a manager)");
        }
    }
}
