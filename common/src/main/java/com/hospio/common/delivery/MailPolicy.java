package com.hospio.common.delivery;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which email addresses are never sent to, and whether email goes out at all. Read by {@link DeliveryService} (for the
 * echo's status) and by messaging-service's sender (which decides). Properties, shared by every service through the same
 * environment:
 * <ul>
 *   <li>{@code hospio.mail.enabled} (env {@code MAIL_ENABLED}, default false): email goes out through SMTP.</li>
 *   <li>{@code hospio.mail.blocked-domains} (env {@code MAIL_BLOCKED_DOMAINS}, comma separated): a domain blocks itself and
 *   every subdomain; {@code *.test} style entries block a whole top-level domain. Defaults to {@link #DEFAULT_BLOCKED}.</li>
 * </ul>
 */
@Component
public class MailPolicy {

    /**
     * Reserved and test domains, the seed hotel's domain (royalsuites.ng may belong to someone real) and the other made-up
     * domains in the seed data and QA scripts.
     */
    public static final String DEFAULT_BLOCKED = "*.test,*.example,*.invalid,*.localhost,example.com,example.org,example.net,hospio-qa.test,"
            + "royalsuites.ng,x.ng,y.ng,supplier.ng,savannahlodge.ng,lagosfreshfoods.ng,ikejadrinks.ng,bluelagoon.ng,qasupplies.ng,"
            + "adaqa.ng,simpashotels.com";

    private final boolean enabled;
    private final List<String> blocked;

    public MailPolicy(@Value("${hospio.mail.enabled:${MAIL_ENABLED:false}}") boolean enabled,
                      @Value("${hospio.mail.blocked-domains:${MAIL_BLOCKED_DOMAINS:" + DEFAULT_BLOCKED + "}}") String blockedDomains) {
        this.enabled = enabled;
        this.blocked = Arrays.stream((blockedDomains == null ? "" : blockedDomains).split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public static MailPolicy defaults(boolean enabled) {
        return new MailPolicy(enabled, DEFAULT_BLOCKED);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<String> blockedDomains() {
        return blocked;
    }

    /** True for an address on a blocked domain, and for anything that is not an address at all. */
    public boolean isTestAddress(String email) {
        String domain = domainOf(email);
        if (domain == null) {
            return true;
        }
        for (String b : blocked) {
            if (b.startsWith("*.")) {
                String tld = b.substring(1); // ".test"
                if (domain.endsWith(tld)) {
                    return true;
                }
            } else if (domain.equals(b) || domain.endsWith("." + b)) {
                return true;
            }
        }
        return false;
    }

    /** What the echo says about an email: sent for real, or not. */
    public String statusFor(Delivery d) {
        if (d.channel() != Delivery.Channel.EMAIL) {
            return Delivery.NOT_CONNECTED;
        }
        if (isTestAddress(d.to())) {
            return Delivery.TEST_ADDRESS;
        }
        return enabled ? Delivery.EMAIL_SENT : Delivery.NOT_CONNECTED;
    }

    static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        int at = e.lastIndexOf('@');
        if (at <= 0 || at == e.length() - 1 || e.indexOf(' ') >= 0) {
            return null;
        }
        String d = e.substring(at + 1);
        while (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        return d.contains(".") && Objects.equals(d, d.strip()) ? d : null;
    }
}
