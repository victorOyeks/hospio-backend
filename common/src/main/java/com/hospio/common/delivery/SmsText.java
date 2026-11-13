package com.hospio.common.delivery;

import java.text.Normalizer;
import java.util.Map;

/**
 * Every SMS fits one page (docs/sms-whatsapp.md): 160 characters of the GSM 7-bit alphabet. One character outside it
 * (₦, "·", curly quotes, an emoji) turns the whole message into Unicode with 70 characters a page, so the text is first
 * brought into GSM (₦ → N, · → -, “” → "), accents dropped, anything else removed; then, if it is still over a page, cut
 * at the last word boundary with "...". A URL is never cut in half: text after it is dropped first, and a message whose
 * URL itself can't fit loses the words before it instead. Characters GSM sends as two ({ } [ ] ~ ^ | \ €) count as two.
 */
public final class SmsText {

    public static final int PAGE = 160;

    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENDED = "^{}\\[~]|€";
    private static final Map<Character, String> SWAP = Map.ofEntries(
            Map.entry('₦', "N"), Map.entry('·', "-"), Map.entry('•', "-"), Map.entry('–', "-"), Map.entry('—', "-"),
            Map.entry('‘', "'"), Map.entry('’', "'"), Map.entry('“', "\""), Map.entry('”', "\""), Map.entry('…', "..."),
            Map.entry('→', "->"), Map.entry('×', "x"), Map.entry(' ', " "), Map.entry('\t', " "));

    private SmsText() {
    }

    /** The text in the GSM alphabet and within one page. */
    public static String fit(String text) {
        String t = gsm(text == null ? "" : text).replaceAll("[ ]{2,}", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
        if (length(t) <= PAGE) {
            return t;
        }
        java.util.regex.Matcher url = java.util.regex.Pattern.compile("https?://\\S+").matcher(t);
        if (url.find()) {
            String link = url.group();
            String before = t.substring(0, url.start()).trim();
            // Keep the words before the link that fit with it; the link itself is whole or the message is just the link.
            int room = PAGE - length(link) - 1;
            if (room <= 0) {
                return link.length() <= PAGE ? link : cut(t, PAGE);
            }
            return (length(before) <= room ? before : cut(before, room)) + " " + link;
        }
        return cut(t, PAGE);
    }

    /** The message in GSM 7-bit characters: known look-alikes swapped, accents dropped, the rest removed. */
    public static String gsm(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (GSM_BASIC.indexOf(c) >= 0 || GSM_EXTENDED.indexOf(c) >= 0) {
                out.append(c);
            } else if (SWAP.containsKey(c)) {
                out.append(SWAP.get(c));
            } else {
                String plain = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                for (char p : plain.toCharArray()) {
                    if (GSM_BASIC.indexOf(p) >= 0) {
                        out.append(p);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Length as the network counts it: GSM extension characters take two. */
    public static int length(String gsmText) {
        int n = 0;
        for (char c : gsmText.toCharArray()) {
            n += GSM_EXTENDED.indexOf(c) >= 0 ? 2 : 1;
        }
        return n;
    }

    private static String cut(String t, int max) {
        String ellipsis = "...";
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (char c : t.toCharArray()) {
            int w = GSM_EXTENDED.indexOf(c) >= 0 ? 2 : 1;
            if (n + w > max - ellipsis.length()) {
                break;
            }
            out.append(c);
            n += w;
        }
        String s = out.toString();
        int space = Math.max(s.lastIndexOf(' '), s.lastIndexOf('\n'));
        if (space > max / 2) {
            s = s.substring(0, space);
        }
        return s.replaceAll("[\\s,;:.-]+$", "") + ellipsis;
    }
}
