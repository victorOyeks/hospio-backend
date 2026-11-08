package com.hospio.hotelconfig.service;

import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.dto.ListItemRequest;
import com.hospio.hotelconfig.dto.PriceListItemRequest;
import com.hospio.hotelconfig.dto.PriceListResponse;
import com.hospio.hotelconfig.entity.ListItem;
import com.hospio.hotelconfig.entity.PriceList;
import com.hospio.hotelconfig.entity.PriceListItem;
import com.hospio.hotelconfig.repository.ListItemRepository;
import com.hospio.hotelconfig.repository.PriceListItemRepository;
import com.hospio.hotelconfig.repository.PriceListRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reasons & lists, and the price lists (minibar, laundry, extras). In-use items are hidden, never deleted. */
@Service
@RequiredArgsConstructor
public class ListsAndPricesService {

    private final ListItemRepository listItems;
    private final PriceListRepository priceLists;
    private final PriceListItemRepository priceListItems;

    @Transactional(readOnly = true)
    public List<ListItem> list(String hotelId, String listName, boolean activeOnly) {
        String name = listName.toUpperCase();
        return activeOnly ? listItems.findByHotelIdAndListNameAndActiveTrueOrderBySortOrderAscLabelAsc(hotelId, name)
                : listItems.findByHotelIdAndListNameOrderBySortOrderAscLabelAsc(hotelId, name);
    }

    @Transactional
    public ListItem saveListItem(String listName, String id, ListItemRequest request) {
        ListItem item = id == null ? ListItem.builder().listName(listName.toUpperCase()).build()
                : listItems.findById(id).orElseThrow(() -> new ResourceNotFoundException("List item not found"));
        item.setLabel(request.label());
        item.setCode(request.code());
        if (request.sortOrder() != null) {
            item.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            item.setActive(request.active());
        }
        return listItems.save(item);
    }

    @Transactional(readOnly = true)
    public List<PriceListResponse> priceLists(String hotelId) {
        return priceLists.findByHotelIdOrderByName(hotelId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PriceListResponse priceList(String hotelId, String kind) {
        return toResponse(priceLists.findByHotelIdAndKind(hotelId, kind.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("No " + kind + " price list yet")));
    }

    @Transactional
    public PriceListResponse savePriceListItem(String kind, String itemId, PriceListItemRequest request) {
        String hotelId = TenantContext.requireHotel();
        PriceList list = priceLists.findByHotelIdAndKind(hotelId, kind.toUpperCase())
                .orElseGet(() -> priceLists.save(PriceList.builder().kind(kind.toUpperCase()).name(kind).build()));
        PriceListItem item = itemId == null ? PriceListItem.builder().priceListId(list.getId()).build()
                : priceListItems.findById(itemId).orElseThrow(() -> new ResourceNotFoundException("Price not found"));
        item.setName(request.name());
        item.setPrice(request.price());
        item.setTaxRate(request.taxRate());
        item.setChargeCategory(request.chargeCategory());
        if (request.active() != null) {
            item.setActive(request.active());
        }
        priceListItems.save(item);
        return toResponse(list);
    }

    private PriceListResponse toResponse(PriceList list) {
        return new PriceListResponse(list.getId(), list.getKind(), list.getName(), list.isActive(), priceListItems.findByPriceListIdOrderByName(list.getId()));
    }
}
