package com.hospio.platform.service;

import com.hospio.common.instrumentation.RequestLoggingSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whose address a public form's request counts as. The landing site sends its forms from its own server, so every visitor
 * would look like that one server; the site therefore names the visitor in {@code X-Client-Address}. That header is believed
 * only when the request itself came from one of our own servers: this machine (loopback and its own addresses, the usual
 * case: the site and the API share a box) or an address listed in {@code hospio.site-addresses} (SITE_ADDRESSES). From anyone
 * else the header is ignored and the caller's own address counts, so nobody can pick the address they are limited by.
 */
@Slf4j
@Component
public class SiteCallers {

    public static final String HEADER = "X-Client-Address";
    private static final Pattern ADDRESS = Pattern.compile("^[0-9A-Fa-f:.]{3,45}$");

    private final Set<String> ours = new LinkedHashSet<>();

    public SiteCallers(@Value("${hospio.site-addresses:}") String configured) {
        ours.add("127.0.0.1");
        ours.add("::1");
        ours.add("0:0:0:0:0:0:0:1");
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    String host = a.getHostAddress();
                    int zone = host.indexOf('%');
                    ours.add(zone > 0 ? host.substring(0, zone) : host);
                }
            }
        } catch (Exception e) {
            log.warn("This machine's own addresses could not be listed ({}); only loopback and SITE_ADDRESSES are trusted", e.getMessage());
        }
        if (configured != null) {
            for (String s : configured.split(",")) {
                if (!s.isBlank()) {
                    ours.add(s.trim());
                }
            }
        }
        log.info("Public forms: the visitor's address in {} is believed from {}", HEADER, ours);
    }

    /** The visitor's address: the one our own site passed on, otherwise the caller's. */
    public String visitorAddress(HttpServletRequest request) {
        String caller = RequestLoggingSupport.getClientIp(request);
        return visitorAddress(caller, request.getHeader(HEADER));
    }

    String visitorAddress(String caller, String claimed) {
        if (claimed != null && caller != null && ours.contains(caller.trim())) {
            String c = claimed.trim();
            if (ADDRESS.matcher(c).matches()) {
                return c;
            }
        }
        return caller;
    }
}
