package com.hospio.payment.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.payment.dto.AssignRoleRequest;
import com.hospio.payment.dto.TerminalRequest;
import com.hospio.payment.entity.PosRoleAssignment;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.service.TerminalService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hotel setup 20.2-20.5 · POS terminals. */
@RestController
@RequestMapping("/terminals")
@RequiredArgsConstructor
public class TerminalController {

    private final TerminalService service;

    @GetMapping
    public ApiResponse<List<PosTerminal>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/assignments")
    public ApiResponse<List<PosRoleAssignment>> assignments() {
        return ApiResponse.ok(service.assignments());
    }

    @PostMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<PosTerminal> add(@Valid @RequestBody TerminalRequest request) {
        return ApiResponse.ok("POS added", service.save(null, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<PosTerminal> update(@PathVariable String id, @Valid @RequestBody TerminalRequest request) {
        return ApiResponse.ok("Saved", service.save(id, request));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("@access.manager()")
    public ApiResponse<PosRoleAssignment> assign(@PathVariable String id, @Valid @RequestBody AssignRoleRequest request) {
        return ApiResponse.ok("Role assigned", service.assign(id, request.role()));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@access.manager()")
    public ApiResponse<PosTerminal> status(@PathVariable String id, @RequestParam PosTerminal.Status status) {
        return ApiResponse.ok("Status changed", service.setStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Void> retire(@PathVariable String id) {
        service.retire(id);
        return ApiResponse.ok("POS removed", null);
    }
}
