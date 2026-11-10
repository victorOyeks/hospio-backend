package com.hospio.messaging.mail;

/** Hands one email to the SMTP relay and returns what it answered. {@link JavaMailGateway} is the real one. */
public interface SmtpGateway {

    record Email(String fromAddress, String fromName, String to, String replyTo, String subject, String html, String text) {
    }

    /** What the relay answered: the Message-ID and its reply line (e.g. "250 2.0.0 OK: queued as ..."). */
    record Accepted(String messageId, String reply) {
    }

    /** Thrown when the relay refuses or cannot be reached; {@code transient} failures are worth one more try. */
    class SendFailure extends RuntimeException {
        private final boolean transientFailure;

        public SendFailure(String message, boolean transientFailure, Throwable cause) {
            super(message, cause);
            this.transientFailure = transientFailure;
        }

        public boolean isTransient() {
            return transientFailure;
        }
    }

    Accepted send(Email email) throws SendFailure;

    /** False when no relay is configured (no host). */
    boolean configured();
}
