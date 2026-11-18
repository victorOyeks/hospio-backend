package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.fnb.client.TenantClient;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.entity.SaleRequest;
import com.hospio.fnb.service.BillService;
import com.hospio.fnb.service.DiscountService;
import com.hospio.fnb.service.SaleRequestService;
import com.hospio.fnb.service.SplitService;
import com.hospio.fnb.service.StaffOutlets;
import com.hospio.fnb.service.TipReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bill of a sale (docs/plan-venue-type.md, phase 4): split it and pay each part, give a discount or a complimentary item, ask a
 * manager for a void or a bigger discount, and the tips report. Who may do what on which sale is decided in the services; the
 * guards here only keep out roles that never touch a bill.
 */
@RestController
@RequiredArgsConstructor
public class BillController {

    static final String READ = OrderController.READ;
    /** Anyone who takes orders or money at an outlet. */
    static final String FLOOR = OrderController.CHARGE + " or @access.manager()";

    private final BillService bills;
    private final SplitService splits;
    private final DiscountService discounts;
    private final SaleRequestService requests;
    private final StaffOutlets staffOutlets;
    private final TipReport tipReport;
    private final TenantClient tenants;

    @GetMapping("/orders/{id}/bill")
    @PreAuthorize(READ)
    public ApiResponse<Bill> bill(@PathVariable String id) {
        return ApiResponse.ok(bills.bill(id));
    }

    @PostMapping("/orders/{id}/split")
    @PreAuthorize(FLOOR)
    public ApiResponse<Bill> split(@PathVariable String id, @RequestBody Bill.SplitRequest request) {
        splits.split(id, request);
        return ApiResponse.ok("Bill split", bills.bill(id));
    }

    @DeleteMapping("/orders/{id}/split")
    @PreAuthorize(FLOOR)
    public ApiResponse<Bill> undoSplit(@PathVariable String id) {
        splits.undo(id);
        return ApiResponse.ok("Split undone", bills.bill(id));
    }

    @PostMapping("/orders/{id}/parts/{partId}/charge-to-room")
    @PreAuthorize(FLOOR)
    public ApiResponse<Bill> chargePart(@PathVariable String id, @PathVariable String partId, @RequestParam(required = false) String roomId) {
        splits.chargePartToRoom(id, partId, roomId);
        return ApiResponse.ok("Charged to the room", bills.bill(id));
    }

    /** Given now when the caller may; otherwise {@code request} is what was sent to a manager. */
    @PostMapping("/orders/{id}/discount")
    @PreAuthorize(FLOOR)
    public ApiResponse<Bill.Outcome> discount(@PathVariable String id, @RequestBody Bill.DiscountRequest request) {
        SaleRequest asked = requests.discount(id, request);
        return outcome(id, asked, Boolean.TRUE.equals(request.complimentary()) ? "Marked complimentary" : "Discount given");
    }

    @DeleteMapping("/orders/{id}/discount")
    @PreAuthorize(FLOOR)
    public ApiResponse<Bill> removeDiscount(@PathVariable String id, @RequestParam(required = false) String lineId) {
        discounts.remove(id, lineId);
        return ApiResponse.ok("Discount removed", bills.bill(id));
    }

    @PostMapping("/orders/{id}/void-request")
    @PreAuthorize(FLOOR)
    public ApiResponse<Bill.Outcome> voidRequest(@PathVariable String id, @RequestBody Bill.VoidRequest request) {
        return outcome(id, requests.requestVoid(id, request), "Done");
    }

    private ApiResponse<Bill.Outcome> outcome(String orderId, SaleRequest asked, String done) {
        Bill bill = bills.bill(orderId);
        if (asked == null) {
            return ApiResponse.ok(done, new Bill.Outcome(bill, null, done));
        }
        Bill.Request view = bill.requests().stream().filter(r -> r.id().equals(asked.getId())).findFirst().orElse(null);
        String message = "Sent to a manager for approval";
        return ApiResponse.ok(message, new Bill.Outcome(bill, view, message));
    }

    /** Requests waiting (or {@code status=ALL}): a manager's are everyone's at the location, anyone else's their own. */
    @GetMapping("/sale-requests")
    @PreAuthorize(READ)
    public ApiResponse<List<Bill.Request>> requests(@RequestParam(required = false) String status, @RequestParam(required = false) Boolean mine) {
        return ApiResponse.ok(requests.list(status, mine));
    }

    /** By the request's id or its approval id. */
    @GetMapping("/sale-requests/{id}")
    @PreAuthorize(READ)
    public ApiResponse<Bill.Request> request(@PathVariable String id) {
        return ApiResponse.ok(requests.get(id));
    }

    // ---- the outlets a person works -------------------------------------------------------------------

    @GetMapping("/staff-outlets/mine")
    public ApiResponse<StaffOutlets.Assignment> myOutlets() {
        return ApiResponse.ok(staffOutlets.mine());
    }

    /** Everyone at the location kept to a list: user id to outlet ids. People not in it work every outlet. */
    @GetMapping("/staff-outlets")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<Map<String, List<String>>> allOutlets() {
        return ApiResponse.ok(tenants.withHotelTenant(() -> staffOutlets.all(com.hospio.common.tenancy.TenantContext.requireHotel())));
    }

    public record OutletList(List<String> outletIds) {
    }

    /** An empty list (or every outlet): the person works every outlet again. Hospio360 sets it during onboarding too. */
    @PutMapping("/staff-outlets/{userId}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<StaffOutlets.Assignment> setOutlets(@PathVariable String userId, @RequestBody OutletList request) {
        return ApiResponse.ok("Outlets saved", tenants.withHotelTenant(() -> staffOutlets.set(userId, request.outletIds())));
    }

    // ---- tips -------------------------------------------------------------------------------------------

    /** Managers, owners and finance: every tip. Anyone else: the tips on the sales they served ({@code mine}). */
    @GetMapping("/tips/report")
    @PreAuthorize(READ)
    public ApiResponse<TipReport.Report> tips(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) String staffId) {
        var user = CurrentUser.require();
        boolean management = user.isPlatform() || user.isManagerOrOwner() || user.has(com.hospio.common.enums.Role.FINANCE);
        return ApiResponse.ok(tipReport.report(from, to, management ? staffId : user.getUserId()));
    }
}
