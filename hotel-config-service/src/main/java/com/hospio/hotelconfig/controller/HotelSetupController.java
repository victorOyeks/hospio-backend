package com.hospio.hotelconfig.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.dto.ApplyTemplateRequest;
import com.hospio.hotelconfig.dto.ListItemRequest;
import com.hospio.hotelconfig.dto.PriceListItemRequest;
import com.hospio.hotelconfig.dto.PriceListResponse;
import com.hospio.hotelconfig.entity.ListItem;
import com.hospio.hotelconfig.service.HotelSetupService;
import com.hospio.hotelconfig.service.ListsAndPricesService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manager · Hotel setup (41 screens) and the owner-only Taxes & currency / Approval limits. */
@RestController
@RequestMapping("/setup")
@RequiredArgsConstructor
public class HotelSetupController {

    private final HotelSetupService setup;
    private final ListsAndPricesService lists;

    @Operation(summary = "Every setup section for the hotel in X-Hotel-Id, stored values laid over the catalogue defaults")
    @GetMapping
    public ApiResponse<Map<String, Map<String, JsonNode>>> all() {
        return ApiResponse.ok(setup.all(TenantContext.requireHotel()));
    }

    @GetMapping("/{section}")
    public ApiResponse<Map<String, JsonNode>> section(@PathVariable String section) {
        return ApiResponse.ok(setup.section(section));
    }

    @Operation(summary = "Save a section's keys: manager or owner; TAXES_CURRENCY and APPROVAL_LIMITS owner only; platform allowed")
    @PutMapping("/{section}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Map<String, JsonNode>> save(@PathVariable String section, @RequestBody Map<String, JsonNode> values) {
        return ApiResponse.ok("Saved", setup.save(section, values));
    }

    @Operation(summary = "Apply a setup template (by templateId from tenant-service, or inline settings) to the hotel in X-Hotel-Id")
    @PostMapping("/apply-template")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Map<String, Map<String, JsonNode>>> applyTemplate(@RequestBody ApplyTemplateRequest request) {
        return ApiResponse.ok("Template applied", setup.applyTemplate(request));
    }

    @GetMapping("/lists/{listName}")
    public ApiResponse<List<ListItem>> list(@PathVariable String listName, @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ApiResponse.ok(lists.list(TenantContext.requireHotel(), listName, activeOnly));
    }

    @PostMapping("/lists/{listName}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<ListItem> addListItem(@PathVariable String listName, @Valid @RequestBody ListItemRequest request) {
        return ApiResponse.ok("Added", lists.saveListItem(listName, null, request));
    }

    @PutMapping("/lists/{listName}/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<ListItem> updateListItem(@PathVariable String listName, @PathVariable String id, @Valid @RequestBody ListItemRequest request) {
        return ApiResponse.ok("Saved", lists.saveListItem(listName, id, request));
    }

    @Operation(summary = "Remove a list entry: it is deactivated, never deleted, so past records keep their label")
    @DeleteMapping("/lists/{listName}/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<ListItem> deleteListItem(@PathVariable String listName, @PathVariable String id) {
        return ApiResponse.ok("Removed", lists.deactivate(listName, id));
    }

    @GetMapping("/price-lists")
    public ApiResponse<List<PriceListResponse>> priceLists() {
        return ApiResponse.ok(lists.priceLists(TenantContext.requireHotel()));
    }

    @GetMapping("/price-lists/{kind}")
    public ApiResponse<PriceListResponse> priceList(@PathVariable String kind) {
        return ApiResponse.ok(lists.priceList(TenantContext.requireHotel(), kind));
    }

    @PostMapping("/price-lists/{kind}/items")
    @PreAuthorize("@access.manager()")
    public ApiResponse<PriceListResponse> addPrice(@PathVariable String kind, @Valid @RequestBody PriceListItemRequest request) {
        return ApiResponse.ok("Added", lists.savePriceListItem(kind, null, request));
    }

    @PutMapping("/price-lists/{kind}/items/{itemId}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<PriceListResponse> updatePrice(@PathVariable String kind, @PathVariable String itemId, @Valid @RequestBody PriceListItemRequest request) {
        return ApiResponse.ok("Saved", lists.savePriceListItem(kind, itemId, request));
    }
}
