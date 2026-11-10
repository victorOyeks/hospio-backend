package com.hospio.messaging.mail;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The one email layout (docs/email.md): the hotel's name as the header, a heading for the kind, the text as paragraphs, the
 * first https link as a button, and a footer. Plain text goes alongside as the alternative part. The input is the plain
 * text every service already writes, so nothing changes for the services that build deliveries.
 */
@Component
public class EmailTemplates {

    public record Rendered(String subject, String html, String text) {
    }

    private record Look(String heading, String button) {
    }

    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+");

    private static final Map<String, Look> LOOKS = Map.ofEntries(
            Map.entry("INVITE", new Look("You're invited", "Accept the invite")),
            Map.entry("TEMP_PASSWORD", new Look("Your temporary password", "Sign in")),
            Map.entry("PASSWORD_RESET", new Look("Your password was reset", "Sign in")),
            Map.entry("BOOKING_CONFIRMATION", new Look("Your booking is confirmed", null)),
            Map.entry("MESSAGE", new Look(null, null)),
            Map.entry("PURCHASE_ORDER", new Look("Purchase order", null)),
            Map.entry("REPORT", new Look("Your report is ready", "Open the report")),
            Map.entry("DAILY_REPORT", new Look("Your report is ready", "Open the report")),
            Map.entry("RECEIPT", new Look("Your receipt", null)),
            Map.entry("FOLIO", new Look("Your stay summary", null)),
            Map.entry("STATEMENT", new Look("Statement", null)),
            Map.entry("PAYMENT_REMINDER", new Look("Payment reminder", null)),
            Map.entry("HANDOVER_PACK", new Look("Your Hospio360 handover pack", "Sign in")),
            Map.entry("FEE_STATEMENT", new Look("Fee statement", null)));

    public Rendered render(String kind, String fromName, String subject, String text) {
        String from = blank(fromName) ? "Hospio360" : fromName.trim();
        String body = text == null ? "" : text.replace("\r\n", "\n").trim();
        String subj = blank(subject) ? from : subject.trim();
        Look look = LOOKS.getOrDefault(kind == null ? "" : kind, new Look(null, null));
        String heading = look.heading() == null ? (Boolean.TRUE.equals(isMessage(kind)) ? null : subj) : look.heading();

        String link = null;
        Matcher m = LINK.matcher(body);
        if (m.find() && look.button() != null) {
            link = trimPunctuation(m.group());
        }

        StringBuilder html = new StringBuilder(2048);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(subj)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f4f5f7;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2430;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f4f5f7;padding:24px 12px;\"><tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border-radius:12px;border:1px solid #e4e6eb;\">")
                .append("<tr><td style=\"padding:20px 28px;border-bottom:1px solid #eceef2;font-size:18px;font-weight:700;color:#1f2430;\">")
                .append(esc(from)).append("</td></tr>")
                .append("<tr><td style=\"padding:24px 28px 8px 28px;\">");
        if (heading != null) {
            html.append("<h1 style=\"margin:0 0 16px 0;font-size:20px;line-height:1.3;color:#1f2430;\">").append(esc(heading)).append("</h1>");
        }
        for (String para : body.split("\\n\\s*\\n")) {
            if (para.isBlank()) {
                continue;
            }
            html.append("<p style=\"margin:0 0 14px 0;font-size:15px;line-height:1.55;color:#33394a;\">")
                    .append(linkify(esc(para.trim())).replace("\n", "<br>")).append("</p>");
        }
        if (link != null) {
            html.append("<p style=\"margin:20px 0 18px 0;\"><a href=\"").append(escAttr(link))
                    .append("\" style=\"display:inline-block;background:#1f5eff;color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;padding:12px 22px;border-radius:8px;\">")
                    .append(esc(look.button())).append("</a></p>");
        }
        html.append("</td></tr>")
                .append("<tr><td style=\"padding:16px 28px 22px 28px;border-top:1px solid #eceef2;font-size:12px;line-height:1.5;color:#7a8091;\">")
                .append("Sent by ").append(esc(from)).append(" with Hospio360.")
                .append(isPlatform(from) ? "" : " Reply to this email to reach " + esc(from) + ".")
                .append("</td></tr></table></td></tr></table></body></html>");

        StringBuilder plain = new StringBuilder();
        plain.append(from).append("\n\n");
        if (heading != null) {
            plain.append(heading).append("\n\n");
        }
        plain.append(body).append("\n\n-- \nSent by ").append(from).append(" with Hospio360.\n");
        return new Rendered(subj, html.toString(), plain.toString());
    }

    private static Boolean isMessage(String kind) {
        return "MESSAGE".equals(kind);
    }

    private static boolean isPlatform(String from) {
        return "Hospio360".equalsIgnoreCase(from);
    }

    /** Links in already-escaped text become anchors. */
    static String linkify(String escaped) {
        Matcher m = LINK.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = trimPunctuation(m.group());
            String rest = m.group().substring(url.length());
            m.appendReplacement(sb, Matcher.quoteReplacement("<a href=\"" + url + "\" style=\"color:#1f5eff;\">" + url + "</a>" + rest));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String trimPunctuation(String url) {
        String u = url;
        while (!u.isEmpty() && ".,;:)".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String escAttr(String s) {
        return esc(s);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
