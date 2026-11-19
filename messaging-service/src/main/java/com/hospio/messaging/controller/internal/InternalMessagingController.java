package com.hospio.messaging.controller.internal;

import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.response.ApiResponse;
import com.hospio.messaging.dto.MessagingRequests;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.OutboundMessage;
import com.hospio.messaging.service.ConversationService;
import com.hospio.messaging.service.NotificationService;
import com.hospio.messaging.service.OutboundService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Other services send a message or a notification directly; providers post inbound guest replies. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMessagingController {

    private final OutboundService outbound;
    private final NotificationService notifications;
    private final ConversationService conversations;
    private final com.hospio.messaging.service.DeliveryOutlook outlook;

    @PostMapping("/send")
    public ApiResponse<OutboundMessage> send(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(outbound.queue(NotificationChannel.valueOf(body.get("channel")), body.get("to"), body.get("subject"),
                body.get("body"), body.get("kind"), body.get("reference")));
    }

    @PostMapping("/notify")
    public ApiResponse<Void> notify(@RequestBody Map<String, String> body) {
        notifications.notify(body.get("eventId"), body.get("userId"), body.get("role"), body.getOrDefault("kind", "INFO"),
                body.get("title"), body.get("body"), body.get("link"));
        return ApiResponse.ok("Queued", null);
    }

    @PostMapping("/inbound")
    public ApiResponse<Conversation> inbound(@RequestParam String hotelId, @Valid @RequestBody MessagingRequests.InboundRequest request) {
        return ApiResponse.ok(conversations.inbound(hotelId, request));
    }

    /**
     * What would become of a guest message sent now (docs/whatsapp-receipts.md): payment-service asks before it tells the desk
     * "Receipt sent", so a receipt skipped (guest messages off), held back (staging allow-list) or not connected says so.
     */
    @GetMapping("/deliveries/outlook")
    public ApiResponse<com.hospio.messaging.service.DeliveryOutlook.Outlook> outlook(@RequestParam String hotelId,
                                                                                  @RequestParam(required = false) String businessId,
                                                                                  @RequestParam(required = false) String kind,
                                                                                  @RequestParam(required = false) String channel,
                                                                                  @RequestParam(required = false) String to) {
        return ApiResponse.ok(outlook.of(hotelId, businessId, kind, channel, to));
    }
}
