package com.hospio.hotelconfig.controller.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.response.ApiResponse;
import com.hospio.hotelconfig.dto.PriceListResponse;
import com.hospio.hotelconfig.entity.ListItem;
import com.hospio.hotelconfig.service.HotelSetupService;
import com.hospio.hotelconfig.service.ListsAndPricesService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every other service reads its rules, lists and prices from here. Service-to-service only. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalSetupController {

    private final HotelSetupService setup;
    private final ListsAndPricesService lists;

    @GetMapping("/settings/{section}")
    public ApiResponse<Map<String, JsonNode>> section(@RequestParam String hotelId, @PathVariable String section) {
        return ApiResponse.ok(setup.section(hotelId, section));
    }

    @GetMapping("/settings/{section}/{key}")
    public ApiResponse<JsonNode> value(@RequestParam String hotelId, @PathVariable String section, @PathVariable String key) {
        return ApiResponse.ok(setup.value(hotelId, section, key));
    }

    @GetMapping("/lists/{listName}")
    public ApiResponse<List<ListItem>> list(@RequestParam String hotelId, @PathVariable String listName) {
        return ApiResponse.ok(lists.list(hotelId, listName, true));
    }

    @GetMapping("/price-lists/{kind}")
    public ApiResponse<PriceListResponse> priceList(@RequestParam String hotelId, @PathVariable String kind) {
        return ApiResponse.ok(lists.priceList(hotelId, kind));
    }

    /** Onboarding J8 · Hotel setup from a template. Runs inside the hotel's business, whoever calls. */
    @PostMapping("/apply-template")
    public ApiResponse<Void> applyTemplate(@RequestParam String hotelId, @RequestBody String templateJson) {
        setup.applyTemplate(hotelId, templateJson);
        return ApiResponse.ok("Template applied", null);
    }
}
