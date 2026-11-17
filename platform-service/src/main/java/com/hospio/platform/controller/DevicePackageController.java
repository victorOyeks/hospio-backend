package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.service.DevicePackageService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Devices and internet supplied to hotels (docs/plan-device-packages.md). Any platform admin puts a hotel's package together (it
 * is part of onboarding); the money roles record payments; only a SUPER_ADMIN changes the catalogue. Owners see their business's
 * packages and managers their hotel's, read-only.
 */
@RestController
@RequiredArgsConstructor
public class DevicePackageController {

    static final String PLATFORM = "hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN','SUPPORT')";

    private final DevicePackageService devices;
    private final com.hospio.platform.service.DeviceInvoiceService invoices;

    @Operation(summary = "The device catalogue: standard items and prices")
    @GetMapping("/admin/devices/catalogue")
    @PreAuthorize(PLATFORM)
    public ApiResponse<List<DevicePackageService.CatalogueView>> catalogue() {
        return ApiResponse.ok(devices.catalogue());
    }

    @Operation(summary = "Add a catalogue item")
    @PostMapping("/admin/devices/catalogue")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<DevicePackageService.CatalogueView> addItem(@RequestBody DevicePackageService.CatalogueRequest r) {
        return ApiResponse.ok("Item added", devices.saveCatalogueItem(null, r));
    }

    @Operation(summary = "Change a catalogue item (packages already put together keep their prices)")
    @PutMapping("/admin/devices/catalogue/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<DevicePackageService.CatalogueView> changeItem(@PathVariable String id, @RequestBody DevicePackageService.CatalogueRequest r) {
        return ApiResponse.ok("Item saved", devices.saveCatalogueItem(id, r));
    }

    @Operation(summary = "Every device package (DRAFT, INSTALLED, PAID_OFF by default)")
    @GetMapping("/admin/devices/packages")
    @PreAuthorize(PLATFORM)
    public ApiResponse<List<DevicePackageService.PackageView>> packages(@RequestParam(required = false) List<String> status) {
        return ApiResponse.ok(devices.all(status));
    }

    @Operation(summary = "A business's device packages")
    @GetMapping("/admin/businesses/{businessId}/device-packages")
    @PreAuthorize(PLATFORM)
    public ApiResponse<List<DevicePackageService.PackageView>> ofBusiness(@PathVariable String businessId) {
        return ApiResponse.ok(devices.forBusiness(businessId));
    }

    @Operation(summary = "A hotel's current device package (null when it has none)")
    @GetMapping("/admin/businesses/{businessId}/hotels/{hotelId}/device-package")
    @PreAuthorize(PLATFORM)
    public ApiResponse<DevicePackageService.PackageView> ofHotel(@PathVariable String businessId, @PathVariable String hotelId) {
        return ApiResponse.ok(devices.forHotel(hotelId));
    }

    @Operation(summary = "Put together or change a hotel's device package while it is a draft")
    @PutMapping("/admin/businesses/{businessId}/hotels/{hotelId}/device-package")
    @PreAuthorize(PLATFORM)
    public ApiResponse<DevicePackageService.PackageView> save(@PathVariable String businessId, @PathVariable String hotelId,
                                                             @RequestBody DevicePackageService.SaveRequest r) {
        return ApiResponse.ok("Device package saved", devices.save(businessId, hotelId, r));
    }

    public record InstallRequest(LocalDate installedOn) {
    }

    @Operation(summary = "The devices are installed: collection starts that day")
    @PostMapping("/admin/device-packages/{id}/install")
    @PreAuthorize(PLATFORM)
    public ApiResponse<DevicePackageService.PackageView> install(@PathVariable String id, @RequestBody(required = false) InstallRequest r) {
        return ApiResponse.ok("Devices marked installed", devices.install(id, r == null ? null : r.installedOn()));
    }

    @Operation(summary = "Record money received for a device package (cash or transfer)")
    @PostMapping("/admin/device-packages/{id}/payments")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<DevicePackageService.PackageView> pay(@PathVariable String id, @RequestBody DevicePackageService.PaymentRequest r) {
        return ApiResponse.ok("Payment recorded", devices.recordPayment(id, r));
    }

    @Operation(summary = "Cancel a device package with nothing collected on it")
    @PostMapping("/admin/device-packages/{id}/cancel")
    @PreAuthorize(PLATFORM)
    public ApiResponse<DevicePackageService.PackageView> cancel(@PathVariable String id) {
        return ApiResponse.ok("Device package cancelled", devices.cancel(id));
    }

    @Operation(summary = "Owner: the business's device packages")
    @GetMapping("/owner/devices")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<DevicePackageService.PackageView>> owner() {
        com.hospio.common.security.HospioUserDetails user = CurrentUser.require();
        List<DevicePackageService.PackageView> all = devices.forBusiness(user.getBusinessId());
        // An owner sees every hotel; a manager reaching this sees only the hotels they work at.
        if (user.isPlatform() || user.has(com.hospio.common.enums.Role.OWNER)) {
            return ApiResponse.ok(all);
        }
        return ApiResponse.ok(all.stream().filter(p -> user.getHotelIds().contains(p.hotelId())).toList());
    }

    @Operation(summary = "Manager: the current hotel's device package (null when it has none)")
    @GetMapping("/devices")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<DevicePackageService.PackageView> hotel() {
        return ApiResponse.ok(devices.forHotel(TenantContext.requireHotel()));
    }

    // ---- monthly invoices (instalments and data plan) -------------------------------------------------------------

    @Operation(summary = "A package's monthly invoices")
    @GetMapping("/admin/device-packages/{id}/invoices")
    @PreAuthorize(PLATFORM)
    public ApiResponse<List<com.hospio.platform.service.DeviceInvoiceService.InvoiceView>> packageInvoices(@PathVariable String id) {
        return ApiResponse.ok(invoices.forPackage(id));
    }

    @Operation(summary = "Every device invoice (DUE, OVERDUE, PAID by default)")
    @GetMapping("/admin/devices/invoices")
    @PreAuthorize(PLATFORM)
    public ApiResponse<List<com.hospio.platform.service.DeviceInvoiceService.InvoiceView>> allInvoices(@RequestParam(required = false) List<String> status) {
        return ApiResponse.ok(invoices.all(status));
    }

    public record MarkPaidRequest(String reference, String note) {
    }

    @Operation(summary = "Mark a device invoice paid (cash or transfer received)")
    @PostMapping("/admin/device-invoices/{id}/mark-paid")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<com.hospio.platform.service.DeviceInvoiceService.InvoiceView> markPaid(@PathVariable String id, @RequestBody MarkPaidRequest r) {
        return ApiResponse.ok("Invoice marked paid", invoices.markPaid(id, r == null ? null : r.reference(), r == null ? null : r.note()));
    }

    @Operation(summary = "Run the device invoices now: issue, debit, overdue (normally 07:00 and 15:00)")
    @PostMapping("/admin/devices/invoices/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<com.hospio.platform.service.DeviceInvoiceService.RunResult> runInvoices() {
        return ApiResponse.ok(invoices.run(LocalDate.now(java.time.ZoneId.of("Africa/Lagos"))));
    }

    @Operation(summary = "Owner (or manager, their hotels): the device invoices")
    @GetMapping("/owner/devices/invoices")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<com.hospio.platform.service.DeviceInvoiceService.InvoiceView>> ownerInvoices() {
        return ApiResponse.ok(invoices.forPackages(owner().data().stream().map(DevicePackageService.PackageView::id).toList()));
    }

    public record PayRequest(String callbackUrl) {
    }

    @Operation(summary = "Owner or manager: pay a device invoice online (a Paystack checkout)")
    @PostMapping("/owner/devices/invoices/{id}/pay")
    @PreAuthorize("@access.manager()")
    public ApiResponse<com.hospio.platform.client.PlatformPaystackClient.Checkout> payOnline(@PathVariable String id, @RequestBody(required = false) PayRequest r) {
        return ApiResponse.ok(invoices.payOnline(id, r == null ? null : r.callbackUrl()));
    }

    @Operation(summary = "Owner or manager: back from Paystack, confirm the payment")
    @PostMapping("/owner/devices/invoices/confirm")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<com.hospio.platform.service.DeviceInvoiceService.InvoiceView> confirm(@RequestParam String reference) {
        return ApiResponse.ok(invoices.confirm(reference));
    }

    @Operation(summary = "Internal: Paystack's webhook (received by messaging-service) for a device invoice payment")
    @PostMapping("/internal/devices/paystack-confirm")
    public ApiResponse<com.hospio.platform.service.DeviceInvoiceService.InvoiceView> internalConfirm(@RequestParam String reference) {
        return ApiResponse.ok(invoices.confirm(reference));
    }
}
