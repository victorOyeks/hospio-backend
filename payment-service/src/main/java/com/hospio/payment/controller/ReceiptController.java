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
 * Receipts (docs/manager-leftovers.md, docs/whatsapp-receipts.md): one payment's, a sale's or a folio's, to view; a payment's receipt
 * sent to the guest on WhatsApp (the only way a payment receipt goes out: no printing, no email). The people who take or look after
 * payments read them: front office, bar, kitchen, laundry, finance, managers and the owner. Emailing a receipt or folio stays for
 * other callers.
 */
@RestController
@RequestMapping("/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    static final String TAKERS = "@access.managerView() or @access.has('FRONT_OFFICE') or @access.has('BAR') or @access.has('KITCHEN') or @access.has('CASHIER') or @access.has('WAITER')"
            + " or @access.has('LAUNDRY') or @access.has('FINANCE')";
    static final String SENDERS = "@access.manager() or @access.atLeast('FRONT_OFFICE','EDIT') or @access.atLeast('BAR','EDIT') or @access.atLeast('CASHIER','EDIT')"
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

    /** Send on WhatsApp: {@code channel} WHATSAPP or left out; PRINT or EMAIL (older screens) is refused in words. */
    @PostMapping("/send")
    @PreAuthorize(SENDERS)
    public ApiResponse<ReceiptService.Resent> send(@RequestBody ReceiptViews.SendRequest request) {
        ReceiptService.Resent r = receipts.send(request);
        return ApiResponse.ok("Receipt " + r.receiptNumber() + " sent on WhatsApp to " + r.sentTo(), r);
    }

    @PostMapping("/email")
    @PreAuthorize(SENDERS)
    public ApiResponse<ReceiptViews.Emailed> email(@RequestBody ReceiptViews.EmailRequest request) {
        ReceiptViews.Emailed e = receipts.email(request);
        return ApiResponse.ok("Receipt emailed to " + e.to(), e);
    }
}
