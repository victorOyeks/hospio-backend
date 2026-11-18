package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.entity.LegalDocument;
import com.hospio.platform.service.LegalService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Legal documents and their online acceptance (docs/legal-acceptance.md). */
@RestController
@RequiredArgsConstructor
public class LegalController {

    private final LegalService legal;

    @Operation(summary = "Public: the version in force of each legal document, and versions announced for later")
    @GetMapping("/public/legal/documents")
    public ApiResponse<Map<String, List<LegalService.DocView>>> documents() {
        return ApiResponse.ok(legal.publicDocuments());
    }

    @Operation(summary = "Signed-in person: the documents they must accept (pending), what is coming with notice, and their records")
    @GetMapping("/legal/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<LegalService.MyLegal> me() {
        return ApiResponse.ok(legal.me());
    }

    @Operation(summary = "Signed-in person accepts the versions in force: {documents:[{key, version}], method?: SELF_SIGN_UP|HANDOVER|PORTAL}")
    @PostMapping("/legal/accept")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<LegalService.MyLegal> accept(@RequestBody LegalService.AcceptRequest request) {
        return ApiResponse.ok("Thank you, accepted", legal.accept(request));
    }

    @Operation(summary = "Owner: the business's acceptance records (owner, staff, device terms, links shared)")
    @GetMapping("/owner/legal/acceptances")
    @PreAuthorize("hasRole('OWNER')")
    public ApiResponse<List<LegalService.RecordView>> owner() {
        return ApiResponse.ok(legal.forBusiness(null));
    }

    @Operation(summary = "Admin: a business's acceptance records, newest first")
    @GetMapping("/admin/legal/acceptances")
    @PreAuthorize(DevicePackageController.PLATFORM)
    public ApiResponse<List<LegalService.RecordView>> business(@RequestParam String businessId) {
        return ApiResponse.ok(legal.forBusiness(businessId));
    }

    @Operation(summary = "Admin: a business's acceptance records as CSV")
    @GetMapping(value = "/admin/legal/acceptances/export", produces = "text/csv")
    @PreAuthorize(DevicePackageController.PLATFORM)
    public ResponseEntity<String> export(@RequestParam String businessId) {
        String name = "acceptances-" + businessId.replaceAll("[^A-Za-z0-9-]", "") + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(legal.csv(businessId));
    }

    @Operation(summary = "Admin (handover step): how the legal links were shared with the owner {channel, sharedOn, note}; not an acceptance")
    @PostMapping("/admin/legal/businesses/{businessId}/links-shared")
    @PreAuthorize(DevicePackageController.PLATFORM)
    public ApiResponse<List<LegalService.RecordView>> linksShared(@PathVariable String businessId, @RequestBody LegalService.LinksSharedRequest request) {
        return ApiResponse.ok("Recorded how the links were shared", legal.linksShared(businessId, request));
    }

    @Operation(summary = "Admin: every version of every legal document")
    @GetMapping("/admin/legal/documents")
    @PreAuthorize(DevicePackageController.PLATFORM)
    public ApiResponse<List<LegalDocument>> versions() {
        return ApiResponse.ok(legal.allVersions());
    }

    @Operation(summary = "Super admin: publish a new version {key, version, title?, effectiveFrom?, material?, summary?}; people accept it at "
            + "their next sign-in from its effective date (a material change needs 30 days' notice)")
    @PostMapping("/admin/legal/documents")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<LegalDocument> publish(@RequestBody LegalService.PublishRequest request) {
        return ApiResponse.ok("New version published", legal.publish(request));
    }
}
