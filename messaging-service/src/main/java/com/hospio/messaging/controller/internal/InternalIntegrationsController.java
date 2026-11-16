package com.hospio.messaging.controller.internal;

import com.hospio.common.integrations.settings.IntegrationProvider;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.common.response.ApiResponse;
import com.hospio.messaging.billing.PaystackClient;
import com.hospio.messaging.mail.MailRouter;
import com.hospio.messaging.text.InfobipClient;
import com.hospio.messaging.text.TermiiClient;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings · Integrations → Test for the providers this service uses (platform-service asks, internal API key). The portal's settings
 * are read fresh first, so a value saved a moment ago is the one tested; each check reads an account or signs in and sends nothing.
 */
@Hidden
@RestController
@RequestMapping("/internal/integrations")
@RequiredArgsConstructor
public class InternalIntegrationsController {

    private final IntegrationSettingsReader integrations;
    private final MailRouter mail;
    private final InfobipClient infobip;
    private final TermiiClient termii;
    private final PaystackClient paystack;

    @PostMapping("/{provider}/test")
    public ApiResponse<IntegrationTestResult> test(@PathVariable String provider) {
        IntegrationProvider p = IntegrationProvider.parse(provider).orElse(null);
        if (p == null || p.owner() != IntegrationProvider.Owner.MESSAGING) {
            return ApiResponse.ok(IntegrationTestResult.failed("messaging-service doesn't use " + provider + ", so it can't test it"));
        }
        integrations.fresh(p.type());
        IntegrationTestResult result = switch (p) {
            case BREVO -> mail.brevo().check();
            case SMTP -> mail.smtp().check();
            case INFOBIP -> infobip.check();
            case TERMII -> termii.check();
            case PAYSTACK -> paystack.check();
            default -> IntegrationTestResult.failed("messaging-service can't test " + p.label());
        };
        return ApiResponse.ok(result);
    }
}
