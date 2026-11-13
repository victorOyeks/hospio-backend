package com.hospio.messaging.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every email's layout (docs/email.md), in the web app's look: DM Sans, the app's ink, line and status colours, rounded
 * cards, and the sending hotel's own brand colour and logo from Hotel setup → Brand (Hospio360's maroon when the hotel has
 * set none, and for platform mail). Each kind of email has its own design: a badge in the tone of what happened (green for
 * a confirmed booking or a payment, red for a cancellation, amber for a reminder), its heading, and its button.
 *
 * <p>The services keep writing plain text; it is laid out here. A paragraph of "Label: value" lines becomes a details
 * card, a temporary password or code becomes a highlighted box, "- " lines become a list, and the first link becomes the
 * button (with the address underneath for clients that block buttons). The same text goes as the plain-text part.
 */
@Component
public class EmailTemplates {

    public record Rendered(String subject, String html, String text) {
    }

    /** The hotel's look: its brand colour (with a light tint and the text colour that reads on it) and logo. */
    public record Brand(String colour, String soft, String onColour, String logoUrl) {

        public static final Brand HOSPIO = new Brand("#4c091e", "#fceef2", "#ffffff", null);

        /** From Hotel setup's values; anything missing or unreadable falls back to Hospio360's look. */
        public static Brand of(String colour, String logoUrl) {
            String c = hex(colour);
            String logo = logoUrl != null && logoUrl.trim().matches("^https://\\S+$") ? logoUrl.trim() : null;
            if (c == null) {
                return logo == null ? HOSPIO : new Brand(HOSPIO.colour, HOSPIO.soft, HOSPIO.onColour, logo);
            }
            return new Brand(c, mix(c, 0.92), luminance(c) > 0.55 ? "#212121" : "#ffffff", logo);
        }

        private static String hex(String v) {
            if (v == null) {
                return null;
            }
            String t = v.trim().toLowerCase(Locale.ROOT);
            if (t.matches("^#[0-9a-f]{3}$")) {
                t = "#" + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2) + t.charAt(3) + t.charAt(3);
            }
            return t.matches("^#[0-9a-f]{6}$") ? t : null;
        }

        private static int[] rgb(String hex) {
            return new int[] {Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16), Integer.parseInt(hex.substring(5, 7), 16)};
        }

        /** The colour mixed toward white ({@code toWhite} 0..1): the soft tint behind cards and badges. */
        private static String mix(String hex, double toWhite) {
            int[] c = rgb(hex);
            return String.format("#%02x%02x%02x", (int) Math.round(c[0] + (255 - c[0]) * toWhite),
                    (int) Math.round(c[1] + (255 - c[1]) * toWhite), (int) Math.round(c[2] + (255 - c[2]) * toWhite));
        }

        private static double luminance(String hex) {
            int[] c = rgb(hex);
            return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255.0;
        }
    }

    /** The app's tokens (packages/ui globals.css). */
    private static final String INK = "#212121";
    private static final String INK_2 = "#616161";
    private static final String INK_3 = "#9e9e9e";
    private static final String LINE = "#e9ecf1";
    private static final String PAGE = "#f6f4f5";
    private static final String SUBTLE = "#f8f8f9";
    private static final String FOOTER = "Hospio360 · hotel management by Oyeks Global";
    private static final String FONT = "'DM Sans',-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private enum Tone {
        BRAND, SUCCESS, WARNING, DANGER, INFO;

        String fg(Brand b) {
            return switch (this) {
                case BRAND -> b.colour();
                case SUCCESS -> "#1e7a45";
                case WARNING -> "#9e7800";
                case DANGER -> "#d92d20";
                case INFO -> "#004ce8";
            };
        }

        String bg(Brand b) {
            return switch (this) {
                case BRAND -> b.soft();
                case SUCCESS -> "#e6f9ed";
                case WARNING -> "#fff7de";
                case DANGER -> "#fef3f2";
                case INFO -> "#eaf1ff";
            };
        }
    }

    /** One kind's design: the badge (its words and tone), the heading (null: the subject), the button's words. */
    private record Look(String badge, Tone tone, String heading, String button) {
    }

    private static final Map<String, Look> LOOKS = Map.ofEntries(
            Map.entry("INVITE", new Look("Invitation", Tone.BRAND, "You're invited", "Accept the invite")),
            Map.entry("TEMP_PASSWORD", new Look("Your account", Tone.BRAND, "Your sign-in details", "Sign in")),
            Map.entry("PASSWORD_RESET", new Look("Security", Tone.WARNING, "Your password was reset", "Sign in")),
            Map.entry("PHONE_CODE", new Look("Verification", Tone.BRAND, "Your code", null)),
            Map.entry("HANDOVER_PACK", new Look("Welcome", Tone.BRAND, "Your hotel is ready on Hospio360", "Sign in")),
            Map.entry("BOOKING_CONFIRMATION", new Look("Booking confirmed", Tone.SUCCESS, "Your booking is confirmed", null)),
            Map.entry("BOOKING_CHANGED", new Look("Booking updated", Tone.INFO, "Your stay has changed", null)),
            Map.entry("BOOKING_CANCELLED", new Look("Booking cancelled", Tone.DANGER, "Your booking is cancelled", null)),
            Map.entry("RECEIPT", new Look("Payment received", Tone.SUCCESS, "Your receipt", "View receipt")),
            Map.entry("FOLIO", new Look("Your bill", Tone.INFO, "Your folio", "View folio")),
            Map.entry("STAY_SUMMARY", new Look("Thank you", Tone.BRAND, "Your stay summary", null)),
            Map.entry("STATEMENT", new Look("Statement", Tone.INFO, null, "View statement")),
            Map.entry("PAYMENT_REMINDER", new Look("Payment due", Tone.WARNING, "Payment reminder", null)),
            Map.entry("FEE_STATEMENT", new Look("Fee statement", Tone.INFO, null, "View statement")),
            Map.entry("PURCHASE_ORDER", new Look("Purchase order", Tone.BRAND, null, null)),
            Map.entry("REPORT", new Look("Report", Tone.INFO, "Your report is ready", "Open the report")),
            Map.entry("DAILY_REPORT", new Look("Daily report", Tone.INFO, "Your report is ready", "Open the report")),
            Map.entry("TICKET_UPDATE", new Look("Support", Tone.INFO, null, "Open the ticket")),
            Map.entry("DECISION", new Look("Approval", Tone.WARNING, null, null)),
            Map.entry("TILL_VARIANCE", new Look("Till", Tone.WARNING, null, null)),
            Map.entry("NOTICE", new Look("Notice", Tone.BRAND, null, null)),
            Map.entry("INVOICE", new Look("Invoice", Tone.INFO, null, "Pay or view the invoice")),
            Map.entry("MESSAGE", new Look("Message", Tone.BRAND, null, null)));

    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+");
    private static final Pattern FIELD = Pattern.compile("^([A-Z][A-Za-z0-9 /&'()·.-]{0,38}):\\s+(.+)$");
    private static final Pattern SECRET = Pattern.compile("(?i)^(temporary password|password|code|pin|your code)$");
    private static final Pattern MONEY_LABEL = Pattern.compile("(?i)^(total|amount|amount due|balance|balance due|paid|grand total|outstanding)$");

    /** With Hospio360's own look (platform mail, tests). */
    public Rendered render(String kind, String fromName, String subject, String text) {
        return render(kind, fromName, subject, text, Brand.HOSPIO);
    }

    public Rendered render(String kind, String fromName, String subject, String text, Brand brand) {
        Brand b = brand == null ? Brand.HOSPIO : brand;
        String from = blank(fromName) ? "Hospio360" : fromName.trim();
        String body = text == null ? "" : text.replace("\r\n", "\n").trim();
        String subj = blank(subject) ? from : subject.trim();
        String k = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        Look look = LOOKS.getOrDefault(k, new Look("Message", Tone.BRAND, null, null));
        Tone tone = "DECISION".equals(k) ? decisionTone(subj) : look.tone();
        String heading = look.heading() != null ? look.heading() : subj;

        // The button: the first link, when this kind has a button (its address also stays visible underneath).
        String link = null;
        Matcher m = LINK.matcher(body);
        if (m.find() && look.button() != null) {
            link = trimPunctuation(m.group());
        }

        StringBuilder h = new StringBuilder(6000);
        String preheader = preheader(body);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"color-scheme\" content=\"light\"><title>").append(esc(subj)).append("</title>")
                .append("<link href=\"https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&display=swap\" rel=\"stylesheet\"></head>")
                .append("<body style=\"margin:0;padding:0;background:").append(PAGE).append(";font-family:").append(FONT).append(";color:").append(INK).append(";\">")
                .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;\">").append(esc(preheader)).append("</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:").append(PAGE).append(";padding:28px 12px;\"><tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;\">");

        // Header band: the hotel's colour, its logo (or its initials) and name.
        h.append("<tr><td style=\"background:").append(b.colour()).append(";border-radius:16px 16px 0 0;padding:22px 32px;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>");
        if (b.logoUrl() != null) {
            h.append("<td style=\"padding-right:12px;\"><img src=\"").append(esc(b.logoUrl())).append("\" alt=\"").append(esc(from))
                    .append("\" height=\"36\" style=\"display:block;height:36px;max-width:160px;border-radius:8px;\"></td>");
        } else {
            h.append("<td style=\"padding-right:12px;\"><div style=\"width:36px;height:36px;line-height:36px;text-align:center;border-radius:10px;")
                    .append("background:rgba(255,255,255,0.18);color:").append(b.onColour()).append(";font-weight:700;font-size:14px;\">")
                    .append(esc(initials(from))).append("</div></td>");
        }
        h.append("<td style=\"color:").append(b.onColour()).append(";font-size:17px;font-weight:700;letter-spacing:-0.01em;\">").append(esc(from)).append("</td>")
                .append("</tr></table></td></tr>");

        // Card: badge, heading, then the body's blocks.
        h.append("<tr><td style=\"background:#ffffff;border:1px solid ").append(LINE).append(";border-top:0;border-radius:0 0 16px 16px;padding:30px 32px 26px 32px;\">")
                .append("<span style=\"display:inline-block;background:").append(tone.bg(b)).append(";color:").append(tone.fg(b))
                .append(";font-size:11px;font-weight:700;letter-spacing:0.06em;text-transform:uppercase;padding:5px 10px;border-radius:99px;\">")
                .append(esc(look.badge())).append("</span>")
                .append("<h1 style=\"margin:14px 0 18px 0;font-size:24px;line-height:1.25;font-weight:700;letter-spacing:-0.02em;color:").append(INK).append(";\">")
                .append(esc(heading)).append("</h1>");

        for (Block block : blocks(body, link)) {
            block.html(h, b);
        }

        if (link != null) {
            h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:22px 0 6px 0;\"><tr><td style=\"border-radius:10px;background:")
                    .append(b.colour()).append(";\"><a href=\"").append(esc(link)).append("\" style=\"display:inline-block;padding:13px 26px;font-size:15px;font-weight:600;color:")
                    .append(b.onColour()).append(";text-decoration:none;border-radius:10px;\">").append(esc(look.button())).append("</a></td></tr></table>")
                    .append("<p style=\"margin:8px 0 0 0;font-size:12px;line-height:1.5;color:").append(INK_3).append(";word-break:break-all;\">Or open: ")
                    .append("<a href=\"").append(esc(link)).append("\" style=\"color:").append(INK_3).append(";\">").append(esc(link)).append("</a></p>");
        }
        h.append("</td></tr>");

        // Footer, outside the card: the same line on every email.
        h.append("<tr><td style=\"padding:18px 32px 0 32px;text-align:center;font-size:12px;line-height:1.6;color:").append(INK_3).append(";\">")
                .append(FOOTER).append("</td></tr></table></td></tr></table></body></html>");

        StringBuilder plain = new StringBuilder();
        plain.append(from).append("\n\n").append(heading).append("\n\n").append(body).append("\n\n-- \n").append(FOOTER).append("\n");
        return new Rendered(subj, h.toString(), plain.toString());
    }

    // ---- the body as blocks ----------------------------------------------------------------------------------------

    private interface Block {
        void html(StringBuilder h, Brand b);
    }

    private record Para(String text) implements Block {
        public void html(StringBuilder h, Brand b) {
            h.append("<p style=\"margin:0 0 14px 0;font-size:15px;line-height:1.6;color:").append(INK_2).append(";\">")
                    .append(linkify(esc(text), b).replace("\n", "<br>")).append("</p>");
        }
    }

    private record Fields(List<String[]> rows) implements Block {
        public void html(StringBuilder h, Brand b) {
            h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:6px 0 18px 0;background:")
                    .append(SUBTLE).append(";border:1px solid ").append(LINE).append(";border-radius:12px;\">");
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                boolean money = MONEY_LABEL.matcher(r[0].trim()).matches();
                String border = i == rows.size() - 1 ? "" : "border-bottom:1px solid " + LINE + ";";
                h.append("<tr><td style=\"padding:12px 16px;").append(border).append("font-size:13px;color:").append(INK_3).append(";width:38%;vertical-align:top;\">")
                        .append(esc(r[0])).append("</td><td style=\"padding:12px 16px;").append(border).append("font-size:").append(money ? "16px" : "14px")
                        .append(";font-weight:").append(money ? "700" : "600").append(";color:").append(money ? b.colour() : INK).append(";text-align:right;\">")
                        .append(linkify(esc(r[1]), b)).append("</td></tr>");
            }
            h.append("</table>");
        }
    }

    private record Secret(String label, String value) implements Block {
        public void html(StringBuilder h, Brand b) {
            h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:6px 0 18px 0;\"><tr><td style=\"background:")
                    .append(b.soft()).append(";border:1px dashed ").append(b.colour()).append(";border-radius:12px;padding:16px 18px;text-align:center;\">")
                    .append("<div style=\"font-size:12px;font-weight:600;letter-spacing:0.04em;text-transform:uppercase;color:").append(INK_2).append(";\">")
                    .append(esc(label)).append("</div><div style=\"margin-top:6px;font-family:'SFMono-Regular',Menlo,Consolas,monospace;font-size:22px;font-weight:700;letter-spacing:0.08em;color:")
                    .append(INK).append(";\">").append(esc(value)).append("</div></td></tr></table>");
        }
    }

    private record Bullets(List<String> items) implements Block {
        public void html(StringBuilder h, Brand b) {
            h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 16px 0;\">");
            for (String item : items) {
                h.append("<tr><td style=\"width:18px;vertical-align:top;padding:4px 0;\"><div style=\"width:6px;height:6px;margin-top:8px;border-radius:3px;background:")
                        .append(b.colour()).append(";\"></div></td><td style=\"padding:4px 0;font-size:15px;line-height:1.55;color:").append(INK_2).append(";\">")
                        .append(linkify(esc(item), b)).append("</td></tr>");
            }
            h.append("</table>");
        }
    }

    /** Paragraphs split by blank lines; inside each, runs of "Label: value" lines, "- " lines and the rest. */
    static List<Block> blocks(String body, String buttonLink) {
        List<Block> out = new ArrayList<>();
        for (String para : body.split("\\n\\s*\\n")) {
            List<String> lines = new ArrayList<>();
            for (String l : para.split("\\n")) {
                String t = l.trim();
                // A line that only carries the button's address ("Sign in at https://…", or the bare link) is the button now.
                if (buttonLink != null && t.contains(buttonLink) && t.replace(buttonLink, "").replaceAll("(?i)(sign in at|open|link|here|:)", "").isBlank()) {
                    continue;
                }
                // "View it online: https://…" when that address is the button: the button says it.
                Matcher lf = FIELD.matcher(t);
                if (buttonLink != null && lf.matches() && trimPunctuation(lf.group(2).trim()).equals(buttonLink)) {
                    continue;
                }
                if (!t.isEmpty()) {
                    lines.add(t);
                }
            }
            List<String[]> fields = new ArrayList<>();
            List<String> bullets = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (String l : lines) {
                Matcher f = FIELD.matcher(l);
                boolean isField = f.matches() && !LINK.matcher(f.group(1)).find() && f.group(2).length() <= 160;
                boolean isBullet = l.startsWith("- ") || l.startsWith("• ") || l.startsWith("* ");
                if (!isField && !fields.isEmpty()) {
                    flushFields(out, fields);
                }
                if (!isBullet && !bullets.isEmpty()) {
                    out.add(new Bullets(List.copyOf(bullets)));
                    bullets.clear();
                }
                if (isField) {
                    if (text.length() > 0) {
                        out.add(new Para(text.toString()));
                        text.setLength(0);
                    }
                    fields.add(new String[] {f.group(1).trim(), f.group(2).trim()});
                } else if (isBullet) {
                    if (text.length() > 0) {
                        out.add(new Para(text.toString()));
                        text.setLength(0);
                    }
                    bullets.add(l.substring(2).trim());
                } else {
                    text.append(text.length() > 0 ? "\n" : "").append(l);
                }
            }
            flushFields(out, fields);
            if (!bullets.isEmpty()) {
                out.add(new Bullets(List.copyOf(bullets)));
            }
            if (text.length() > 0) {
                out.add(new Para(text.toString()));
            }
        }
        return out;
    }

    /** A temporary password or code gets its own box; the other fields share a card. */
    private static void flushFields(List<Block> out, List<String[]> fields) {
        if (fields.isEmpty()) {
            return;
        }
        List<String[]> card = new ArrayList<>();
        for (String[] f : fields) {
            if (SECRET.matcher(f[0]).matches()) {
                if (!card.isEmpty()) {
                    out.add(new Fields(List.copyOf(card)));
                    card.clear();
                }
                out.add(new Secret(f[0], f[1].replaceAll("\\s*\\(.*\\)$", "")));
            } else {
                card.add(f);
            }
        }
        if (!card.isEmpty()) {
            out.add(new Fields(List.copyOf(card)));
        }
        fields.clear();
    }

    private static Tone decisionTone(String subject) {
        String s = subject.toLowerCase(Locale.ROOT);
        return s.contains("approved") ? Tone.SUCCESS : s.contains("declined") ? Tone.DANGER : Tone.WARNING;
    }

    /** The first sentence, for the inbox preview line. */
    private static String preheader(String body) {
        String flat = LINK.matcher(body).replaceAll("").replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 117) + "..." : flat;
    }

    private static String initials(String name) {
        StringBuilder s = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0)) && s.length() < 2) {
                s.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return s.length() == 0 ? "H" : s.toString();
    }

    /** Links in already-escaped text become anchors in the brand colour. */
    static String linkify(String escaped, Brand b) {
        Matcher m = LINK.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = trimPunctuation(m.group());
            String rest = m.group().substring(url.length());
            m.appendReplacement(sb, Matcher.quoteReplacement("<a href=\"" + url + "\" style=\"color:" + b.colour() + ";font-weight:600;\">" + url + "</a>" + rest));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String linkify(String escaped) {
        return linkify(escaped, Brand.HOSPIO);
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
