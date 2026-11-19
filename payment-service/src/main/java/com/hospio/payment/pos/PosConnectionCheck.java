package com.hospio.payment.pos;

import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.payment.controller.internal.InternalIntegrationsController;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The admin portal's "Payment provider (POS)" connection check, as payment-service sees it (platform-service asks, GET
 * /internal/integrations/status): the server's POS provider (used by hotels without their own keys), how many hotels have complete
 * Moniepoint and OPay keys, and, for each provider some hotel uses, whether its address answers (the same check as Settings ·
 * Integrations → Test). Moniepoint and OPay have no read-only sign-in: every call with a hotel's keys starts or reads a payment, so
 * no hotel's keys are used here and nothing is ever pushed to a terminal.
 *
 * <p>Status: CONNECTED when hotels have keys and their providers answer; ERROR when a provider some hotel uses doesn't answer, has
 * no address, or the saved keys can't be opened; TEST_MODE when no hotel has keys and the server runs the logging (test) provider,
 * which charges nothing; NOT_CONFIGURED when no hotel has keys and the server's provider isn't connected (payments are refused).
 */
@Slf4j
@Service
public class PosConnectionCheck {

    /** The one row: key payment-provider, its status and a sentence or two of what was found. */
    public record ConnectionCheck(String key, String status, String detail) {
    }

    private static final ExecutorService LIVE = Executors.newVirtualThreadPerTaskExecutor();

    private final HotelPosService hotels;
    private final MoniepointApi moniepoint;
    private final OpayApi opay;
    private final String serverProvider;
    private final Duration timeout;

    @Autowired
    public PosConnectionCheck(HotelPosService hotels, MoniepointApi moniepoint, OpayApi opay,
                              @Value("${hospio.pos.provider:logging}") String serverProvider,
                              @Value("${hospio.integrations.check-timeout-ms:3500}") long timeoutMs) {
        this.hotels = hotels;
        this.moniepoint = moniepoint;
        this.opay = opay;
        this.serverProvider = serverProvider == null ? "logging" : serverProvider.trim().toLowerCase(Locale.ROOT);
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public ConnectionCheck run() {
        List<HotelPosService.Status> statuses = hotels.statuses();
        long withMoniepoint = statuses.stream().filter(s -> s.providers().contains(HotelPosService.MONIEPOINT)).count();
        long withOpay = statuses.stream().filter(s -> s.providers().contains(HotelPosService.OPAY)).count();
        long paused = statuses.stream().filter(HotelPosService.Status::posPaused).count();
        boolean test = "logging".equals(serverProvider);
        String others = test ? "test provider: nothing is charged"
                : "card payments are refused (the server's provider, " + serverProvider + ", isn't connected)";
        String pausedNote = paused == 0 ? "" : " " + hotelsText(paused) + " POS paused.";

        if (withMoniepoint == 0 && withOpay == 0) {
            return new ConnectionCheck("payment-provider", test ? "TEST_MODE" : "NOT_CONFIGURED",
                    (test ? "Test provider: nothing is charged." : "No real provider: " + others + ".")
                            + " No location has Moniepoint or OPay keys saved." + pausedNote);
        }
        if (!hotels.keyStoreReady()) {
            return new ConnectionCheck("payment-provider", "ERROR", hotelsText(withMoniepoint + withOpay - both(statuses))
                    + " keys saved, but the key store has no SECRETS_KEY, so they can't be opened and their card payments fail." + pausedNote);
        }
        CompletableFuture<IntegrationTestResult> mp = withMoniepoint > 0 ? live("Moniepoint", moniepoint.baseUrl()) : null;
        CompletableFuture<IntegrationTestResult> op = withOpay > 0 ? live("OPay", opay.baseUrl()) : null;
        List<String> parts = new ArrayList<>();
        boolean ok = true;
        if (mp != null) {
            IntegrationTestResult r = mp.join();
            ok &= r.ok();
            parts.add("Moniepoint: " + hotelsText(withMoniepoint) + " keys; " + lower(r.message()));
        }
        if (op != null) {
            IntegrationTestResult r = op.join();
            ok &= r.ok();
            parts.add("OPay: " + hotelsText(withOpay) + " keys; " + lower(r.message()));
        }
        String detail = String.join(". ", parts) + ". Locations without keys: " + others + "." + pausedNote
                + " Checked without any location's keys; nothing is pushed to a terminal.";
        return new ConnectionCheck("payment-provider", ok ? "CONNECTED" : "ERROR", detail);
    }

    private CompletableFuture<IntegrationTestResult> live(String label, String url) {
        long ms = timeout.toMillis();
        return CompletableFuture.supplyAsync(() -> InternalIntegrationsController.reachable(label, url, timeout), LIVE)
                .completeOnTimeout(IntegrationTestResult.failed(label + " didn't answer within " + seconds(ms)), ms, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    log.warn("POS connection check for {} failed: {}", label, cause.getClass().getSimpleName());
                    return IntegrationTestResult.failed(label + " couldn't be checked: " + cause.getClass().getSimpleName());
                });
    }

    /** Hotels holding both providers' keys, so the key-store message counts each hotel once. */
    private static long both(List<HotelPosService.Status> statuses) {
        return statuses.stream().filter(s -> s.providers().contains(HotelPosService.MONIEPOINT) && s.providers().contains(HotelPosService.OPAY)).count();
    }

    private static String hotelsText(long n) {
        return n + (n == 1 ? " location has" : " locations have");
    }

    /** The test message mid-sentence: "Moniepoint answered at …" stays, the payments note is said once at the end. */
    private static String lower(String message) {
        String m = message == null ? "" : message.trim();
        int cut = m.indexOf(". Payments still need");
        return cut > 0 ? m.substring(0, cut) : m;
    }

    private static String seconds(long ms) {
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0)) + " s";
    }
}
