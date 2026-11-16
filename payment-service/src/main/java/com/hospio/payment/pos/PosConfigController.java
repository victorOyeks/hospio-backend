package com.hospio.payment.pos;

import com.hospio.common.billing.FeeDebitDtos.DebitRequest;
import com.hospio.common.billing.FeeDebitDtos.DebitResult;
import com.hospio.common.billing.FeeDebitDtos.ProviderState;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hotel POS keys (docs/pos-providers.md): Hospio admins set them, the hotel's managers and owner see them masked. The hotel-keyed
 * OPay and Moniepoint webhooks. And the internal routes platform-service uses for the daily fee debit and the POS pause.
 */
@RestController
@RequiredArgsConstructor
public class PosConfigController {

    private final HotelPosService hotels;
    private final HotelPosRouter router;
    private final PaymentService payments;
    private final PaymentRequestRepository paymentRows;
    private final FeeDebitService feeDebits;

    public record PosConfigRequest(String businessId, String provider, Map<String, String> fields) {
    }

    @Operation(summary = "SUPER_ADMIN: a hotel's POS provider and its keys, masked")
    @GetMapping("/admin/hotels/{hotelId}/pos-config")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<HotelPosService.View> adminView(@PathVariable String hotelId) {
        return ApiResponse.ok(hotels.view(hotelId));
    }

    @Operation(summary = "SUPER_ADMIN: set a hotel's POS provider and keys (a field left blank keeps its saved value)")
    @PutMapping("/admin/hotels/{hotelId}/pos-config")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<HotelPosService.View> adminSave(@PathVariable String hotelId, @RequestBody PosConfigRequest request) {
        return ApiResponse.ok("POS keys saved", hotels.save(request.businessId(), hotelId, request.provider(), request.fields()));
    }

    @Operation(summary = "SUPER_ADMIN: remove a hotel's POS keys (its card payments go back to the server's POS setting)")
    @org.springframework.web.bind.annotation.DeleteMapping("/admin/hotels/{hotelId}/pos-config")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<HotelPosService.View> adminRemove(@PathVariable String hotelId) {
        return ApiResponse.ok("POS keys removed", hotels.remove(hotelId));
    }

    @Operation(summary = "Manager or owner: the hotel's POS provider and keys, masked (read only; Hospio360 sets them)")
    @GetMapping("/pos-config")
    @PreAuthorize("@access.manager()")
    public ApiResponse<HotelPosService.View> hotelView() {
        return ApiResponse.ok(hotels.view(TenantContext.requireHotel()));
    }

    // ---- webhooks (public; each checks the provider's signature with the hotel's keys) -----------------

    @Hidden
    @PostMapping("/webhooks/pos/opay")
    public ProviderDtos.OpayAck opayWebhook(@RequestBody String raw) {
        router.opayWebhook(raw).ifPresent(r -> payments.applyWebhook("opay", r));
        return ProviderDtos.OpayAck.OK;
    }

    @Hidden
    @PostMapping("/webhooks/pos/moniepoint")
    public ResponseEntity<ApiResponse<Void>> moniepointWebhook(@RequestBody String raw,
                                                               @RequestHeader(value = "moniepoint-webhook-id", required = false) String id,
                                                               @RequestHeader(value = "moniepoint-webhook-timestamp", required = false) String timestamp,
                                                               @RequestHeader(value = "moniepoint-webhook-signature", required = false) String signature) {
        var result = router.moniepointWebhook(raw, id, timestamp, signature, paymentId -> TenantContext.runWith(
                com.hospio.common.tenancy.Tenant.system("moniepoint-webhook"),
                () -> paymentRows.findById(paymentId).map(p -> p.getHotelId()).orElse(null)));
        if (result.isEmpty()) {
            return ResponseEntity.status(401).body(ApiResponse.error("Not accepted"));
        }
        payments.applyWebhook("moniepoint", result.get());
        return ResponseEntity.ok(ApiResponse.ok("Received", null));
    }

    // ---- internal: platform-service --------------------------------------------------------------------

    @Hidden
    @GetMapping("/internal/fee-debit/providers")
    public ApiResponse<List<ProviderState>> debitProviders(@RequestParam String hotelId) {
        return ApiResponse.ok(feeDebits.providers(hotelId));
    }

    @Hidden
    @PostMapping("/internal/fee-debit")
    public ApiResponse<DebitResult> debit(@RequestBody DebitRequest request) {
        return ApiResponse.ok(feeDebits.debit(request));
    }

    @Hidden
    @GetMapping("/internal/fee-debit/{reference}")
    public ApiResponse<DebitResult> debitStatus(@PathVariable String reference, @RequestParam String hotelId) {
        return ApiResponse.ok(feeDebits.status(hotelId, reference));
    }

    @Hidden
    @PostMapping("/internal/pos/pause")
    public ApiResponse<Void> pause(@RequestParam String businessId, @RequestParam String hotelId, @RequestParam boolean paused) {
        hotels.setPaused(businessId, hotelId, paused);
        return ApiResponse.ok(paused ? "POS paused" : "POS resumed", null);
    }
}
