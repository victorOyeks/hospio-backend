package com.hospio.messaging.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Termii's JSON (docs/sms-whatsapp.md): the send request and answer of {@code POST /api/sms/send}, and its delivery reports. */
public final class TermiiDtos {

    private TermiiDtos() {
    }

    /** {@code {"api_key","to","from","sms","type":"plain","channel":"dnd"}}: every field always sent. */
    @JsonPropertyOrder({"api_key", "to", "from", "sms", "type", "channel"})
    public record SendRequest(@JsonProperty("api_key") String apiKey, String to, String from, String sms, String type, String channel) {
    }

    /**
     * {@code {"code":"ok","message_id":"…","message_id_str":"…","message":"Successfully Sent","balance":…,"user":"…"}};
     * on a refusal only {@code message} says why. Numeric ids are read as text.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(String code, @JsonProperty("message_id") String messageId, @JsonProperty("message_id_str") String messageIdStr,
                               String message) {
    }

    /**
     * A delivery report: {@code {"type":"outbound","id":…,"message_id":"…","receiver":"234…","sender":"Hospio360",
     * "status":"DELIVERED","channel":"dnd",…}}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Report(String type, String id, @JsonProperty("message_id") String messageId, @JsonProperty("message_id_str") String messageIdStr,
                         String receiver, String sender, String status, String channel) {
    }
}
