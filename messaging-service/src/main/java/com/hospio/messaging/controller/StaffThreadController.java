package com.hospio.messaging.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.messaging.dto.ThreadDtos;
import com.hospio.messaging.service.StaffThreadService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff threads: Owner portal · Messaging and the Inbox every role has (docs/owner-portal.md). */
@RestController
@RequestMapping("/threads")
@RequiredArgsConstructor
public class StaffThreadController {

    private final StaffThreadService threads;

    @Operation(summary = "The caller's staff threads, newest first (whole business without X-Hotel-Id)")
    @GetMapping
    public ApiResponse<List<ThreadDtos.ThreadRow>> mine() {
        return ApiResponse.ok(threads.mine());
    }

    @Operation(summary = "Start a thread at the X-Hotel-Id hotel: to one person, or (owner) to the hotel's managers")
    @PostMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<ThreadDtos.ThreadSent> start(@Valid @RequestBody ThreadDtos.NewThread request) {
        return ApiResponse.ok("Message sent", threads.start(request));
    }

    @Operation(summary = "Owner: the same message to every manager of every hotel, one thread each")
    @PostMapping("/managers")
    @PreAuthorize("@access.owner()")
    public ApiResponse<ThreadDtos.BroadcastSent> broadcast(@Valid @RequestBody ThreadDtos.Broadcast request) {
        ThreadDtos.BroadcastSent sent = threads.broadcastToManagers(request);
        return ApiResponse.ok("Sent to " + sent.count() + (sent.count() == 1 ? " manager" : " managers"), sent);
    }

    @Operation(summary = "One thread with its messages; marks it read for the caller")
    @GetMapping("/{id}")
    public ApiResponse<ThreadDtos.ThreadDetail> open(@PathVariable String id) {
        return ApiResponse.ok(threads.open(id));
    }

    @PostMapping("/{id}/reply")
    public ApiResponse<ThreadDtos.ThreadSent> reply(@PathVariable String id, @Valid @RequestBody ThreadDtos.Reply request) {
        return ApiResponse.ok("Sent", threads.reply(id, request));
    }
}
