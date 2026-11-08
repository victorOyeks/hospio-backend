package com.hospio.hotelconfig.dto;

import com.hospio.hotelconfig.entity.PriceListItem;
import java.util.List;

public record PriceListResponse(String id, String kind, String name, boolean active, List<PriceListItem> items) {
}
