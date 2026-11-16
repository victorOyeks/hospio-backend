package com.hospio.finance.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.finance.dto.OutletSalesSummary;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The typed reads of the other services' answers, and what each call gives when a service fails. */
class PeerServicesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 15);

    private HttpServer server;
    /** path → [status, body]; anything else answers 500. */
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private PeerServices peers;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Object[] a = answers.getOrDefault(ex.getRequestURI().getPath(), new Object[] {500, "{}"});
            byte[] out = ((String) a[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders((int) a[0], out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        peers = new PeerServices(url, url, url, url, url, url, url, mock(JwtUtil.class), "k");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String path, String data) {
        answers.put(path, new Object[] {200, "{\"status\":true,\"message\":\"OK\",\"requestTime\":\"2026-11-15T10:00:00Z\",\"data\":" + data + "}"});
    }

    @Test
    void theDaySummaryReadsWithItsStaysAndMissingListsAreEmpty() {
        answer("/internal/day-summary", "{\"date\":\"2026-11-15\",\"arrivals\":3,\"arrivalsPending\":1,\"departures\":2,\"departuresPending\":0,"
                + "\"inHouse\":9,\"noShows\":0,\"roomsOccupied\":9,\"pendingArrivals\":[{\"bookingId\":\"b1\",\"reference\":\"HBT1\",\"guestName\":\"Ada\","
                + "\"roomNumber\":\"101\",\"checkInDate\":\"2026-11-15\",\"checkOutDate\":\"2026-11-16\"}]}");
        PeerServices.Day d = peers.day("h1", DAY);
        assertThat(d.arrivals()).isEqualTo(3);
        assertThat(d.inHouse()).isEqualTo(9);
        assertThat(d.pendingArrivals()).extracting(PeerServices.Stay::reference).containsExactly("HBT1");
        assertThat(d.pendingDepartures()).isEmpty();
        assertThat(d.departingToday()).isEmpty();
    }

    @Test
    void anUnreachableReservationServiceStopsTheDayButNotTheSoftRead() {
        assertThatThrownBy(() -> peers.day("h1", DAY)).isInstanceOf(ExternalServiceException.class).hasMessageContaining("can't be read");
        answer("/internal/day-summary", "null");
        assertThatThrownBy(() -> peers.day("h1", DAY)).isInstanceOf(ExternalServiceException.class).hasMessageContaining("returned nothing");
        assertThat(peers.daySoft("h1", DAY)).isEmpty();
    }

    @Test
    void failuresGiveEachCallsOwnFallback() {
        assertThat(peers.roomCount("h1")).isEqualTo(-1);
        assertThat(peers.pendingApprovals("h1")).isNull();
        assertThat(peers.outletSales("h1", DAY)).isEmpty();
        assertThat(peers.openOrders("h1", DAY)).isEmpty();
        assertThat(peers.contact("u1")).isEmpty();
        assertThat(peers.withRole("h1", "HOUSEKEEPING")).isEmpty();
        assertThat(peers.business("b1")).isEmpty();
        assertThatThrownBy(() -> peers.hotels("b1")).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> peers.openBalances("h1")).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> peers.markNoShows("h1", DAY)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void countsListsAndBalancesRead() {
        answer("/internal/rooms", "[{\"id\":\"r1\",\"number\":\"101\"},{\"id\":\"r2\",\"number\":\"102\"}]");
        answer("/internal/approvals/pending-count", "4");
        answer("/internal/arrivals/no-show", "2");
        answer("/internal/folios/open-balances", "[{\"folioId\":\"f1\",\"bookingId\":\"b1\",\"guestName\":\"Ada\",\"roomNumber\":\"101\",\"balance\":15000.50},"
                + "{\"folioId\":\"f2\",\"bookingId\":\"b2\",\"guestName\":\"Obi\",\"roomNumber\":\"102\",\"balance\":null}]");
        answer("/internal/users/with-role", "[{\"id\":\"u1\",\"fullName\":\"Grace\",\"email\":\"g@x.ng\",\"phone\":\"080\",\"roles\":[\"HOUSEKEEPING\"]}]");
        answer("/internal/businesses/b1/hotels", "[{\"id\":\"h1\",\"name\":\"Simpas Ikeja\",\"roomCount\":12}]");
        assertThat(peers.roomCount("h1")).isEqualTo(2);
        assertThat(peers.pendingApprovals("h1")).isEqualTo(4);
        assertThat(peers.markNoShows("h1", DAY)).isEqualTo(2);
        assertThat(peers.openBalances("h1")).extracting(PeerServices.Owing::balance).containsExactly(new java.math.BigDecimal("15000.50"), java.math.BigDecimal.ZERO);
        assertThat(peers.withRole("h1", "HOUSEKEEPING")).extracting(PeerServices.Contact::fullName).containsExactly("Grace");
        assertThat(peers.hotels("b1")).containsExactly(new PeerServices.Hotel("h1", "Simpas Ikeja"));
    }

    @Test
    void openOrdersAndOutletSalesKeepTheirShape() {
        answer("/internal/orders/open", "{\"openTabs\":1,\"unsettled\":0,\"outlets\":[{\"outletId\":\"o1\",\"name\":\"Bar\",\"kind\":\"BAR\",\"openTabs\":1,"
                + "\"unsettled\":0,\"orders\":[{\"id\":\"x\",\"number\":\"B-0004\",\"kind\":\"TAB\",\"tableNo\":\"B2\",\"roomNumber\":null,\"total\":12000}]}]}");
        answer("/internal/sales", "{\"date\":\"2026-11-15\",\"outlets\":[{\"outletId\":\"o1\",\"name\":\"Bar\",\"kind\":\"BAR\",\"orders\":2,\"sales\":5000,"
                + "\"paidAtOutlet\":3000,\"chargedToRooms\":2000,\"cancelled\":0,\"byMethod\":{\"CASH\":3000},\"topItems\":[{\"name\":\"Zobo\",\"qty\":2,\"sales\":5000}]}],"
                + "\"totals\":{\"orders\":2,\"sales\":5000,\"paidAtOutlet\":3000,\"chargedToRooms\":2000,\"cancelled\":0,\"byMethod\":{\"CASH\":3000}}}");
        PeerServices.OpenOrders o = peers.openOrders("h1", DAY).orElseThrow();
        assertThat(o.outlets().get(0).orders().get(0).total()).isEqualByComparingTo("12000");
        assertThat(o.outlets().get(0).orders().get(0).kind()).isEqualTo("TAB");
        OutletSalesSummary s = peers.outletSales("h1", DAY).orElseThrow();
        assertThat(s.date()).isEqualTo(DAY);
        assertThat(s.outlets().get(0).topItems().get(0).name()).isEqualTo("Zobo");
        assertThat(s.totals().byMethod()).containsEntry("CASH", new java.math.BigDecimal("3000"));
    }
}
