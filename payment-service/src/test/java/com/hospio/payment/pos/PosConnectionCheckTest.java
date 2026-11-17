package com.hospio.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.payment.controller.internal.InternalIntegrationsController;
import com.hospio.payment.pos.PosConnectionCheck.ConnectionCheck;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The admin portal's "Payment provider (POS)" check: the test provider is said plainly, hotels with keys are counted, each provider
 * a hotel uses is asked whether its address answers, and no hotel's keys are ever used (nothing is pushed to a terminal).
 */
class PosConnectionCheckTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile long delayMs;
    private HotelPosService hotels;
    private MoniepointApi moniepoint;
    private OpayApi opay;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        hotels = mock(HotelPosService.class);
        when(hotels.keyStoreReady()).thenReturn(true);
        moniepoint = mock(MoniepointApi.class);
        opay = mock(OpayApi.class);
        when(moniepoint.baseUrl()).thenReturn(url() + "/moniepoint");
        when(opay.baseUrl()).thenReturn(url() + "/opay");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ConnectionCheck run(String serverProvider, long timeoutMs) {
        return new PosConnectionCheck(hotels, moniepoint, opay, serverProvider, timeoutMs).run();
    }

    private void neverUsesAHotelsKeys() {
        verify(hotels, never()).credentials(anyString(), anyString());
        verify(hotels, never()).all(anyString());
        verify(moniepoint, never()).push(any(), any(), any(), any());
        verify(opay, never()).createOrder(any(), any(), any(), any());
    }

    @Test
    void noHotelKeysOnTheLoggingProviderIsTestModeSaidPlainly() {
        when(hotels.statuses()).thenReturn(List.of());
        ConnectionCheck c = run("logging", 3000);
        assertThat(c.key()).isEqualTo("payment-provider");
        assertThat(c.status()).isEqualTo("TEST_MODE");
        assertThat(c.detail()).startsWith("Test provider: nothing is charged.").contains("No hotel has Moniepoint or OPay keys saved");
        assertThat(hits).hasValue(0);
        neverUsesAHotelsKeys();
    }

    @Test
    void noHotelKeysOnAPlaceholderProviderIsNotConfigured() {
        when(hotels.statuses()).thenReturn(List.of(new HotelPosService.Status("h9", List.of(), true)));
        ConnectionCheck c = run("moniepoint", 3000);
        assertThat(c.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(c.detail()).contains("card payments are refused", "1 hotel has POS paused");
    }

    @Test
    void hotelsWithKeysAreCountedAndEachProviderInUseIsAsked() {
        when(hotels.statuses()).thenReturn(List.of(
                new HotelPosService.Status("h1", List.of("MONIEPOINT", "OPAY"), false),
                new HotelPosService.Status("h2", List.of("OPAY"), false)));
        ConnectionCheck c = run("logging", 3000);
        assertThat(c.status()).isEqualTo("CONNECTED");
        assertThat(c.detail()).contains("Moniepoint: 1 hotel has keys; Moniepoint answered at " + url() + "/moniepoint (HTTP 404)",
                "OPay: 2 hotels have keys; OPay answered", "Hotels without keys: test provider: nothing is charged",
                "nothing is pushed to a terminal");
        assertThat(hits).hasValue(2);
        neverUsesAHotelsKeys();
    }

    @Test
    void aProviderWithoutAnAddressIsAnError() {
        when(hotels.statuses()).thenReturn(List.of(new HotelPosService.Status("h1", List.of("MONIEPOINT"), false)));
        when(moniepoint.baseUrl()).thenReturn("");
        ConnectionCheck c = run("logging", 3000);
        assertThat(c.status()).isEqualTo("ERROR");
        assertThat(c.detail()).contains("Moniepoint has no address");
    }

    @Test
    void aSlowProviderIsAnErrorWithinTheTimeout() {
        when(hotels.statuses()).thenReturn(List.of(new HotelPosService.Status("h1", List.of("OPAY"), false)));
        delayMs = 3000;
        long t0 = System.nanoTime();
        ConnectionCheck c = run("logging", 300);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(c.status()).isEqualTo("ERROR");
        assertThat(c.detail()).contains("OPay didn't answer within 0.3 s");
        assertThat(ms).isLessThan(2000);
    }

    @Test
    void keysThatCantBeOpenedAreAnError() {
        when(hotels.statuses()).thenReturn(List.of(new HotelPosService.Status("h1", List.of("MONIEPOINT", "OPAY"), false)));
        when(hotels.keyStoreReady()).thenReturn(false);
        ConnectionCheck c = run("logging", 3000);
        assertThat(c.status()).isEqualTo("ERROR");
        assertThat(c.detail()).contains("1 hotel has keys saved, but the key store has no SECRETS_KEY");
        assertThat(hits).hasValue(0);
    }

    @Test
    void theEndpointAnswersTheCheck() {
        when(hotels.statuses()).thenReturn(List.of());
        PosConnectionCheck check = new PosConnectionCheck(hotels, moniepoint, opay, "logging", 3000);
        InternalIntegrationsController controller = new InternalIntegrationsController(null, moniepoint, opay, check);
        assertThat(controller.status().data().status()).isEqualTo("TEST_MODE");
    }
}
