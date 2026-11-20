package com.hospio.platform.service;

import com.hospio.platform.entity.HotelApplication;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Opens the service's package-private list filter and sort to the repository test. */
public final class HotelApplicationServiceProbe {

    private HotelApplicationServiceProbe() {
    }

    public static Specification<HotelApplication> spec(String monthlySales) {
        return HotelApplicationService.spec(null, null, null, HotelApplicationService.salesFilter(monthlySales));
    }

    public static Sort order(String sort) {
        return HotelApplicationService.order(sort);
    }
}
