package com.hospio.platform.client;

import com.hospio.common.billing.DailySales;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A hotel's sales for one Lagos day, from each service's {@code GET /internal/sales/daily} (docs/plan-daily-fees.md): folio,
 * outlets (fnb), laundry (housekeeping), counter refunds (payment), no-show deposits (reservation). Throws when any service doesn't
 * answer: a day is never worked out on part of its sales; the job tries it again on its next run.
 */
@Component
public class DailySalesClient {

    private static final ParameterizedTypeReference<ApiResponse<DailySales>> SALES = new ParameterizedTypeReference<>() {
    };

    private final Map<String, RestClient> clients = new LinkedHashMap<>();

    public DailySalesClient(@Value("${services.folio.url}") String folioUrl, @Value("${services.fnb.url}") String fnbUrl,
                            @Value("${services.housekeeping.url}") String housekeepingUrl, @Value("${services.payment.url}") String paymentUrl,
                            @Value("${services.reservation.url}") String reservationUrl,
                            JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        clients.put("folio", ServiceClients.system(folioUrl, jwtUtil, internalApiKey));
        clients.put("fnb", ServiceClients.system(fnbUrl, jwtUtil, internalApiKey));
        clients.put("housekeeping", ServiceClients.system(housekeepingUrl, jwtUtil, internalApiKey));
        clients.put("payment", ServiceClients.system(paymentUrl, jwtUtil, internalApiKey));
        clients.put("reservation", ServiceClients.system(reservationUrl, jwtUtil, internalApiKey));
    }

    public List<DailySales.Line> sales(String hotelId, LocalDate date) {
        List<DailySales.Line> out = new ArrayList<>();
        for (Map.Entry<String, RestClient> c : clients.entrySet()) {
            ApiResponse<DailySales> body;
            try {
                body = c.getValue().get().uri(b -> b.path("/internal/sales/daily").queryParam("hotelId", hotelId).queryParam("date", date).build())
                        .header("X-Hotel-Id", hotelId).retrieve().body(SALES);
            } catch (Exception e) {
                throw new IllegalStateException(c.getKey() + "-service didn't give its sales for " + date + ": " + e.getMessage(), e);
            }
            if (body == null || body.data() == null || body.data().lines() == null) {
                throw new IllegalStateException(c.getKey() + "-service answered without sales lines for " + date);
            }
            out.addAll(body.data().lines());
        }
        return out;
    }
}
