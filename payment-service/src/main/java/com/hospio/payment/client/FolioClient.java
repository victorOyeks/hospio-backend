package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The booking a folio belongs to, so a payment taken on a folio also counts on its booking. */
@Slf4j
@Component
public class FolioClient {

    private final RestClient restClient;

    public FolioClient(@Value("${services.folio.url}") String folioUrl, JwtUtil jwtUtil,
                       @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(folioUrl, jwtUtil, internalApiKey);
    }

    /** Empty when the folio has no booking (a company account) or folio-service can't be reached. */
    public Optional<String> bookingOf(String folioId) {
        try {
            JsonNode body = restClient.get().uri("/internal/folios/{id}", folioId).retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("data").path("bookingId").asText(null);
            return Optional.ofNullable(id == null || id.isBlank() ? null : id);
        } catch (Exception e) {
            log.warn("Could not read folio {} from folio-service: {}", folioId, e.getMessage());
            return Optional.empty();
        }
    }
}
