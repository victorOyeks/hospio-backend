package com.hospio.fnb.client;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Whether a booking has an open bill, so a charge to the room isn't lost on a folio that can't take it. */
@Component
public class FolioClient {

    private final RestClient restClient;

    public FolioClient(@Value("${services.folio.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    /** True when the booking has an open folio; false when it has none (404); throws when folio-service can't answer. */
    public boolean hasOpenFolio(String bookingId) {
        try {
            restClient.get().uri("/internal/folios/open?bookingId={b}", bookingId).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new BadRequestException("Can't check the room's bill right now");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't check the room's bill right now");
        }
    }
}
