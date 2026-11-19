package com.hospio.platform.system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Which units System health looks at, all fixed in configuration (docs/system-health.md): the fourteen services and the gateway
 * (unit {@code <unit-prefix><key>-service}, health at {@code services.<key>.url + /actuator/health}), the web apps
 * ({@code SYSTEM_HEALTH_FRONTENDS} = {@code unit=port=Name,...}), the public hostnames whose certificates are checked, and the
 * folders whose size is shown. Nothing a request sends can add to these lists.
 */
@Component
public class SystemHealthConfig {

    /** The services in the order the page lists them when all is well, with what each does. */
    static final Map<String, String> SERVICES = new LinkedHashMap<>();

    static {
        SERVICES.put("identity", "Sign-in, users and roles");
        SERVICES.put("tenant", "Businesses and locations");
        SERVICES.put("hotel-config", "Hotel setup");
        SERVICES.put("property", "Rooms and floors");
        SERVICES.put("reservation", "Bookings and guests");
        SERVICES.put("folio", "Guest bills");
        SERVICES.put("payment", "Payments and POS");
        SERVICES.put("fnb", "Restaurant and bar");
        SERVICES.put("housekeeping", "Housekeeping and laundry");
        SERVICES.put("maintenance", "Maintenance");
        SERVICES.put("inventory", "Stores and stock");
        SERVICES.put("finance", "Finance, tills and staff");
        SERVICES.put("messaging", "Email, WhatsApp and the bell");
        SERVICES.put("platform", "Admin portal and billing");
    }

    public record Target(String key, String name, String kind, String unit, Integer port, String healthUrl, String commitFile) {
    }

    private final boolean enabled;
    private final List<Target> targets;
    private final List<String> certHosts;
    private final Map<String, String> dirs;
    private final String backupNote;
    private final String environment;

    public SystemHealthConfig(Environment env,
                              @Value("${hospio.system-health.enabled:true}") boolean enabled,
                              @Value("${hospio.system-health.unit-prefix:hospio-}") String unitPrefix,
                              @Value("${hospio.system-health.gateway-url:http://localhost:9200}") String gatewayUrl,
                              @Value("${hospio.system-health.deploy-dir:/opt/hospio-staging}") String deployDir,
                              @Value("${hospio.system-health.apps-dir:/home/devoyeks/apps}") String appsDir,
                              @Value("${hospio.system-health.frontends:}") String frontends,
                              @Value("${hospio.system-health.cert-hosts:}") String certHosts,
                              @Value("${hospio.system-health.dirs:}") String dirs,
                              @Value("${hospio.system-health.backups:}") String backupNote,
                              @Value("${hospio.system-health.environment:}") String environment,
                              @Value("${spring.profiles.active:}") String profiles,
                              @Value("${hospio.apps.admin-url:}") String adminUrl) {
        this.enabled = enabled;
        List<Target> t = new ArrayList<>();
        SERVICES.forEach((key, what) -> {
            String url = env.getProperty("services." + key.replace("-", "") + ".url");
            t.add(new Target(key + "-service", what, "API", unitPrefix + key + "-service", port(url),
                    url == null ? null : url + "/actuator/health", deployDir + "/" + key + "-service/app.sha"));
        });
        t.add(new Target("api-gateway", "Front door for every app (api-staging)", "API", unitPrefix + "api-gateway", port(gatewayUrl),
                gatewayUrl + "/actuator/health", deployDir + "/api-gateway/app.sha"));
        for (String item : split(frontends)) {
            String[] p = item.split("=", 3);
            if (p.length == 3 && UnitProbe.UNIT.matcher(p[0].trim()).matches()) {
                Integer port = parseInt(p[1].trim());
                if (port != null) {
                    t.add(new Target(p[0].trim(), p[2].trim(), "WEB", p[0].trim(), port, "http://127.0.0.1:" + port + "/",
                            appsDir + "/" + p[0].trim() + "/current/.sha"));
                }
            }
        }
        this.targets = List.copyOf(t);
        this.certHosts = split(certHosts).stream().filter(h -> h.matches("^[A-Za-z0-9.-]{1,253}$")).toList();
        Map<String, String> d = new LinkedHashMap<>();
        for (String item : split(dirs)) {
            String[] p = item.split("=", 2);
            if (p.length == 2 && p[1].trim().startsWith("/")) {
                d.put(p[0].trim(), p[1].trim());
            }
        }
        this.dirs = d;
        this.backupNote = backupNote == null ? "" : backupNote.trim();
        this.environment = environment(environment, profiles, adminUrl);
    }

    /**
     * dev, staging or production: HOSPIO_ENV when set, else an active Spring profile named dev/local/staging, else the admin portal's
     * address (one with "staging", "localhost" or "dev" in it is not production). Production unless something says otherwise.
     */
    static String environment(String explicit, String profiles, String adminUrl) {
        String e = explicit == null ? "" : explicit.trim().toLowerCase(java.util.Locale.ROOT);
        if (!e.isEmpty()) {
            return e.startsWith("prod") ? "production" : e.contains("stag") ? "staging" : "dev";
        }
        for (String p : split(profiles)) {
            String q = p.toLowerCase(java.util.Locale.ROOT);
            if (q.contains("stag")) {
                return "staging";
            }
            if (q.equals("dev") || q.equals("local") || q.equals("test")) {
                return "dev";
            }
        }
        String a = adminUrl == null ? "" : adminUrl.toLowerCase(java.util.Locale.ROOT);
        if (a.contains("staging")) {
            return "staging";
        }
        if (a.contains("localhost") || a.contains("-dev") || a.contains("dev.")) {
            return "dev";
        }
        return "production";
    }

    public String environment() {
        return environment;
    }

    /** Alerts are on by default only in production; the switch saved in Settings overrides this. */
    public boolean alertsDefault() {
        return "production".equals(environment);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Target> targets() {
        return targets;
    }

    public List<String> certHosts() {
        return certHosts;
    }

    public Map<String, String> dirs() {
        return dirs;
    }

    /** What the server's backups are, in words; empty = no scheduled backup. */
    public String backupNote() {
        return backupNote;
    }

    static List<String> split(String s) {
        if (!StringUtils.hasText(s)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            if (StringUtils.hasText(part)) {
                out.add(part.trim());
            }
        }
        return out;
    }

    static Integer port(String url) {
        if (url == null) {
            return null;
        }
        try {
            int p = java.net.URI.create(url).getPort();
            return p > 0 ? p : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
