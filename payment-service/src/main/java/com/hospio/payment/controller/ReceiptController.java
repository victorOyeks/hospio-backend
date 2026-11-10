package com.hospio.payment.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.payment.service.ReceiptService;
import com.hospio.payment.service.ReceiptViews;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receipts to print and email (docs/manager-leftovers.md): one payment's, a sale's or a folio's. The people who take or look after
 * payments read them: front office, bar, kitchen, laundry, finance, managers and the owner.
 */
@RestController
@RequestMapping("/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    static final String TAKERS = "@access.managerView() or @access.has('FRONT_OFFICE') or @access.has('BAR') or @access.has('KITCHEN')"
            + " or @access.has('LAUNDRY') or @access.has('FINANCE')";
    static final String SENDERS = "@access.manager() or @access.atLeast('FRONT_OFFICE','EDIT') or @access.atLeast('BAR','EDIT')"
            + " or @access.atLeast('KITCHEN','EDIT') or @access.atLeast('LAUNDRY','EDIT') or @access.atLeast('FINANCE','EDIT')";

    private final ReceiptService receipts;

    @GetMapping("/{paymentId}")
    @PreAuthorize(TAKERS)
    public ApiResponse<ReceiptViews.ReceiptView> one(@PathVariable String paymentId) {
        return ApiResponse.ok(receipts.view(paymentId));
    }

    @GetMapping
    @PreAuthorize(TAKERS)
    public ApiResponse<List<ReceiptViews.ReceiptView>> of(@RequestParam(required = false) String orderId,
                                                          @RequestParam(required = false) String folioId) {
        return ApiResponse.ok(receipts.forRef(orderId, folioId));
    }

    @PostMapping("/email")
    @PreAuthorize(SENDERS)
    public ApiResponse<ReceiptViews.Emailed> email(@RequestBody ReceiptViews.EmailRequest request) {
        ReceiptViews.Emailed e = receipts.email(request);
        return ApiResponse.ok("Receipt emailed to " + e.to(), e);
    }
}
