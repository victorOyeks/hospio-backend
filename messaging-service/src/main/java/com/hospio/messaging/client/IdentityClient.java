package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** People for staff threads: who a message goes to (name, phone, business) and a hotel's managers. */
@Slf4j
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${services.identity.url}") String identityUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(identityUrl, jwtUtil, internalApiKey);
    }

    public record Contact(String id, String fullName, String email, String phone, String businessId, String status, List<String> roles) {
    }

    /** GET /internal/users/{id}/contact; empty when there is no such person. Throws when identity can't be reached. */
    public Optional<Contact> contact(String userId) {
        try {
            JsonNode body = restClient.get().uri("/internal/users/{id}/contact", userId).retrieve().body(JsonNode.class);
            JsonNode d = data(body);
            return d == null ? Optional.empty() : Optional.of(contact(d));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** GET /internal/users/with-role: active holders of a role at a hotel. Throws when identity can't be reached. */
    public List<Contact> withRole(String hotelId, String role) {
        JsonNode body = restClient.get().uri(b -> b.path("/internal/users/with-role").queryParam("hotelId", hotelId).queryParam("role", role).build())
                .retrieve().body(JsonNode.class);
        JsonNode d = data(body);
        List<Contact> out = new ArrayList<>();
        if (d != null && d.isArray()) {
            d.forEach(n -> out.add(contact(n)));
        }
        return out;
    }

    private static JsonNode data(JsonNode body) {
        JsonNode d = body == null ? null : body.path("data");
        return d == null || d.isMissingNode() || d.isNull() ? null : d;
    }

    private static Contact contact(JsonNode n) {
        List<String> roles = new ArrayList<>();
        if (n.path("roles").isArray()) {
            n.path("roles").forEach(r -> roles.add(r.asText()));
        }
        return new Contact(n.path("id").asText(null), n.path("fullName").asText(null), n.path("email").asText(null),
                n.path("phone").asText(null), n.path("businessId").asText(null), n.path("status").asText(null), roles);
    }
}
