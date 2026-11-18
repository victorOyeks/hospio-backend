package com.hospio.messaging.mail;

import java.util.Locale;
import java.util.Set;

/**
 * Addresses that can never receive mail (docs/email.md, "Test addresses are never sent"): the reserved top-level domains
 * {@code .test}, {@code .example}, {@code .invalid}, {@code .localhost} and {@code .local} (RFC 2606 / 6761 / 6762), and
 * {@code example.com}, {@code example.org}, {@code example.net} with any subdomain. Email to them is recorded SKIPPED with
 * {@link #REASON} and never handed to Brevo or SMTP, on every server: sending only produces bounces that hurt the sender's
 * reputation and hide real failures in the connection checks.
 */
public final class TestAddresses {

    /** What the delivery record says (it starts with "Not sent:", so the connection checks never count it as an outcome). */
    public static final String REASON = "Not sent: test address";

    private static final Set<String> TLDS = Set.of("test", "example", "invalid", "localhost", "local");
    private static final Set<String> DOMAINS = Set.of("example.com", "example.org", "example.net");

    private TestAddresses() {
    }

    /** Whether the address's domain is a reserved test domain. Blank, or no domain at all, is not one (it is simply invalid). */
    public static boolean reserved(String address) {
        String domain = domain(address);
        if (domain == null) {
            return false;
        }
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (TLDS.contains(tld)) {
            return true;
        }
        for (String d : DOMAINS) {
            if (domain.equals(d) || domain.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /** The part after the last {@code @}, lower-case, without a trailing dot or a closing bracket; null when there is none. */
    static String domain(String address) {
        if (address == null) {
            return null;
        }
        String a = address.trim();
        int at = a.lastIndexOf('@');
        if (at < 0 || at == a.length() - 1) {
            return null;
        }
        String d = a.substring(at + 1).toLowerCase(Locale.ROOT).trim();
        while (d.endsWith(".") || d.endsWith(">")) {
            d = d.substring(0, d.length() - 1);
        }
        return d.isEmpty() ? null : d;
    }
}
