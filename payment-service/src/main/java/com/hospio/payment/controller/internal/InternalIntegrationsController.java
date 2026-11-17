package com.hospio.payment.controller.internal;

import com.hospio.common.integrations.settings.IntegrationProvider;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.common.response.ApiResponse;
import com.hospio.payment.pos.MoniepointApi;
import com.hospio.payment.pos.OpayApi;
import com.hospio.payment.pos.PosConnectionCheck;
import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings · Integrations → Test for the POS providers (platform-service asks, internal API key). Every call to Moniepoint or OPay needs
 * a hotel's own keys and would start a payment, so the test only checks that the address in force answers at all; it never sends a
 * payment, a payout or a hotel's keys. The fee debit has no test (it would move money).
 */
@Hidden
@RestController
@RequestMapping("/internal/integrations")
@RequiredArgsConstructor
public class InternalIntegrationsController {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private final IntegrationSettingsReader integrations;
    private final MoniepointApi moniepoint;
    private final OpayApi opay;
    private final PosConnectionCheck posCheck;

    /** The admin portal's "Payment provider (POS)" connection check (platform-service asks): read-only, no hotel's keys, no payment. */
    @GetMapping("/status")
    public ApiResponse<PosConnectionCheck.ConnectionCheck> status() {
        return ApiResponse.ok(posCheck.run());
    }

    @PostMapping("/{provider}/test")
    public ApiResponse<IntegrationTestResult> test(@PathVariable String provider) {
        IntegrationProvider p = IntegrationProvider.parse(provider).orElse(null);
        if (p == null || p.owner() != IntegrationProvider.Owner.PAYMENT) {
            return ApiResponse.ok(IntegrationTestResult.failed("payment-service doesn't use " + provider + ", so it can't test it"));
        }
        integrations.fresh(p.type());
        return ApiResponse.ok(switch (p) {
            case MONIEPOINT -> reachable(p.label(), moniepoint.baseUrl());
            case OPAY -> reachable(p.label(), opay.baseUrl());
            default -> IntegrationTestResult.failed("There's no safe live check for " + p.label() + ": testing it would move money");
        });
    }

    /** Any HTTP answer means the address is right and reachable; the hotels' keys are what then decide each payment. */
    static IntegrationTestResult reachable(String label, String url) {
        return reachable(label, url, Duration.ofSeconds(15));
    }

    /** As above, waiting at most {@code timeout} for the answer (the connection checks use a few seconds). */
    public static IntegrationTestResult reachable(String label, String url, Duration timeout) {
        if (url == null || url.isBlank()) {
            return IntegrationTestResult.failed(label + " has no address, saved here or on the server");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.trim())).timeout(timeout).GET().build();
            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return IntegrationTestResult.ok(label + " answered at " + url.trim() + " (HTTP " + res.statusCode()
                    + "). Payments still need each hotel's own keys");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IntegrationTestResult.failed("Interrupted");
        } catch (Exception e) {
            return IntegrationTestResult.failed(label + " didn't answer at " + url.trim() + ": " + e.getClass().getSimpleName());
        }
    }
}
