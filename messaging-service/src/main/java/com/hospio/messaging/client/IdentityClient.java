package com.hospio.messaging.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    /** A person as identity's /internal/users/{id}/contact and /internal/users/with-role give them (UserContactResponse), the fields read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserContact(String id, String fullName, String email, String phone, String businessId, String status, List<String> roles) {
    }

    private static final ParameterizedTypeReference<ApiResponse<UserContact>> ONE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<UserContact>>> MANY = new ParameterizedTypeReference<>() {
    };

    /** GET /internal/users/{id}/contact; empty when there is no such person. Throws when identity can't be reached. */
    public Optional<Contact> contact(String userId) {
        try {
            ApiResponse<UserContact> body = restClient.get().uri("/internal/users/{id}/contact", userId).retrieve().body(ONE);
            return body == null || body.data() == null ? Optional.empty() : Optional.of(contact(body.data()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** GET /internal/users/with-role: active holders of a role at a hotel. Throws when identity can't be reached. */
    public List<Contact> withRole(String hotelId, String role) {
        ApiResponse<List<UserContact>> body = restClient.get().uri(b -> b.path("/internal/users/with-role").queryParam("hotelId", hotelId).queryParam("role", role).build())
                .retrieve().body(MANY);
        List<Contact> out = new ArrayList<>();
        if (body != null && body.data() != null) {
            body.data().forEach(n -> out.add(contact(n)));
        }
        return out;
    }

    private static Contact contact(UserContact n) {
        List<String> roles = n.roles() == null ? new ArrayList<>() : new ArrayList<>(n.roles());
        return new Contact(n.id(), n.fullName(), n.email(), n.phone(), n.businessId(), n.status(), roles);
    }
}
