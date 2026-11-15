package com.hospio.reservation.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.reservation.service.StayPageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The guest's booking page, opened from the "View booking" button on a WhatsApp message (docs/sms-whatsapp.md). Public: the
 * signed token in the path is the only permission, and an unknown or altered token is "not valid" (404).
 */
@RestController
@RequestMapping("/public/stays")
@RequiredArgsConstructor
public class PublicStayController {

    private final StayPageService stays;

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<StayPageService.StayPage>> stay(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(stays.page(token)));
    }
}
