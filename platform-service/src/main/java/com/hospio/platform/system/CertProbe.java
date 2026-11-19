package com.hospio.platform.system;

import com.hospio.platform.system.SystemHealthDtos.Cert;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Days until each public hostname's TLS certificate expires, read from a TLS handshake with this server's nginx on port 443
 * ({@code cert-address}, 127.0.0.1 by default) naming the host (SNI): the certificate this server hands out and has to renew, also
 * for a name that reaches the public through Cloudflare. The certificate is only read, never trusted for anything: the handshake
 * accepts any certificate so an expired or wrong one can still be reported, and nothing is sent after it. A certificate that doesn't
 * name the host is reported as an error. Each host is looked at once an hour.
 */
@Slf4j
@Component
public class CertProbe {

    static final Duration EVERY = Duration.ofHours(1);
    private static final int TIMEOUT_MS = 4000;

    private final String address;
    private final Map<String, Cert> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> checkedAt = new ConcurrentHashMap<>();

    public CertProbe(@org.springframework.beans.factory.annotation.Value("${hospio.system-health.cert-address:127.0.0.1}") String address) {
        this.address = address;
    }

    /** warnDays: under this many days a certificate is a warning; under 7 (or expired) a problem. */
    public List<Cert> probe(List<String> hosts, int warnDays) {
        Instant now = Instant.now();
        return hosts.stream().map(h -> {
            Instant at = checkedAt.get(h);
            Cert c = cache.get(h);
            if (c == null || at == null || at.plus(EVERY).isBefore(now) || c.error() != null && at.plus(Duration.ofMinutes(5)).isBefore(now)) {
                c = check(h);
                cache.put(h, c);
                checkedAt.put(h, now);
            }
            return withLevel(c, warnDays, now);
        }).toList();
    }

    static Cert withLevel(Cert c, int warnDays, Instant now) {
        if (c.expiresAt() == null) {
            return new Cert(c.host(), null, null, c.issuer(), c.error(), SystemHealthDtos.WARNING);
        }
        long days = Duration.between(now, c.expiresAt()).toDays();
        String level = days < 7 ? SystemHealthDtos.PROBLEM : days < warnDays ? SystemHealthDtos.WARNING : SystemHealthDtos.OK;
        return new Cert(c.host(), days, c.expiresAt(), c.issuer(), null, level);
    }

    Cert check(String host) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new ReadOnly()}, new SecureRandom());
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
                s.connect(new InetSocketAddress(address, 443), TIMEOUT_MS);
                s.setSoTimeout(TIMEOUT_MS);
                SSLParameters p = s.getSSLParameters();
                p.setServerNames(List.of(new SNIHostName(host)));
                s.setSSLParameters(p);
                s.startHandshake();
                Certificate[] chain = s.getSession().getPeerCertificates();
                X509Certificate leaf = (X509Certificate) chain[0];
                String issuer = leaf.getIssuerX500Principal().getName();
                int o = issuer.indexOf("O=");
                String org = o >= 0 ? issuer.substring(o + 2).split(",")[0] : issuer;
                if (!covers(leaf, host)) {
                    return new Cert(host, null, null, org, "This server's certificate for this name doesn't cover it (nginx has no site for it?)",
                            SystemHealthDtos.WARNING);
                }
                return new Cert(host, null, leaf.getNotAfter().toInstant(), org, null, SystemHealthDtos.OK);
            }
        } catch (Exception e) {
            log.info("System health: certificate of {} not readable: {}", host, e.toString());
            return new Cert(host, null, null, null, "Couldn't read the certificate: " + e.getClass().getSimpleName(), SystemHealthDtos.WARNING);
        }
    }

    static boolean covers(X509Certificate cert, String host) {
        try {
            java.util.Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names == null) {
                return false;
            }
            for (List<?> n : names) {
                if (n.size() >= 2 && Integer.valueOf(2).equals(n.get(0)) && n.get(1) instanceof String name) {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if (lower.equals(host.toLowerCase(java.util.Locale.ROOT))
                            || lower.startsWith("*.") && host.toLowerCase(java.util.Locale.ROOT).endsWith(lower.substring(1))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Accepts any certificate: only its dates are read (see the class comment). */
    private static final class ReadOnly implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // not used: this side never asks for a client certificate
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // read only, see the class comment
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
