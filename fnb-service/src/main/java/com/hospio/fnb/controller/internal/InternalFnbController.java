package com.hospio.fnb.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.service.OrderService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalFnbController {

    private final OrderService orders;

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderResponse> order(@PathVariable String id) {
        return ApiResponse.ok(orders.get(id));
    }
}
