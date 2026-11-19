package com.hospio.payment.dto;

/**
 * What became of a WhatsApp receipt, as far as is known when the desk sends it (docs/whatsapp-receipts.md, "What the desk is told"):
 * {@code outcome} QUEUED (on its way; the provider answers later), SKIPPED (the hotel's guest messages are off or suspended, or the
 * business is suspended: {@code reason} says which), HELD_BACK (staging's allow-list), NOT_CONNECTED (WhatsApp off on this server),
 * NO_PHONE, or UNKNOWN (messaging-service couldn't be asked; the receipt was still handed over). {@code guestMessagesOn}: the hotel's
 * guest messages are on and not suspended (null when unknown).
 */
public record ReceiptOutlook(String outcome, String reason, Boolean guestMessagesOn) {

    public static final String QUEUED = "QUEUED";
    public static final String SKIPPED = "SKIPPED";
    public static final String HELD_BACK = "HELD_BACK";
    public static final String NOT_CONNECTED = "NOT_CONNECTED";
    public static final String NO_PHONE = "NO_PHONE";
    public static final String UNKNOWN = "UNKNOWN";

    public static ReceiptOutlook unknown() {
        return new ReceiptOutlook(UNKNOWN, null, null);
    }

    public static ReceiptOutlook noPhone(Boolean guestMessagesOn) {
        return new ReceiptOutlook(NO_PHONE, "No phone number for the guest", guestMessagesOn);
    }

    /** Whether the receipt really went on its way. */
    public boolean sent() {
        return QUEUED.equals(outcome) || UNKNOWN.equals(outcome);
    }

    /** The words the desk sees for a receipt sent to {@code to} (the API's message; the apps word it the same way). */
    public String message(String receiptNumber, String to) {
        String r = receiptNumber == null || receiptNumber.isBlank() ? "Receipt" : "Receipt " + receiptNumber;
        return switch (outcome == null ? UNKNOWN : outcome) {
            case QUEUED, UNKNOWN -> r + " sent on WhatsApp to " + to;
            case SKIPPED -> "Receipt not sent: " + (reason == null || reason.isBlank() ? "guest messages are off for this hotel" : lower(reason));
            case HELD_BACK -> "Receipt held back (test server): " + to + " is not on this server's allow-list";
            case NOT_CONNECTED -> "Receipt not sent: WhatsApp is not connected on this server";
            case NO_PHONE -> "Receipt not sent: no phone number";
            default -> r + " sent on WhatsApp to " + to;
        };
    }

    private static String lower(String s) {
        String t = s.trim();
        if (t.startsWith("Not sent: ")) {
            t = t.substring("Not sent: ".length());
        }
        // A channel's name keeps its capitals: "WhatsApp is switched off for this hotel (…)", "Email …" reads "email".
        if (t.startsWith("WhatsApp") || t.startsWith("SMS")) {
            return t;
        }
        return t.isEmpty() ? t : Character.toLowerCase(t.charAt(0)) + t.substring(1);
    }
}
