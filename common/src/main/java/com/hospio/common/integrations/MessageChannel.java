package com.hospio.common.integrations;

import com.hospio.common.enums.NotificationChannel;

/** A way to reach a guest or staff member outside the app: WhatsApp Business, SMS or email. */
public interface MessageChannel {

    NotificationChannel kind();

    /** Returns the provider's message reference. Throws on a transient failure so the worker retries. */
    String send(String to, String subject, String body);
}
