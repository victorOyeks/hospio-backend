package com.hospio.common.integrations.settings;

/** A provider's live check from the admin portal: whether it worked and, in a sentence, what was found. Never moves money. */
public record IntegrationTestResult(boolean ok, String message) {

    public static IntegrationTestResult ok(String message) {
        return new IntegrationTestResult(true, message);
    }

    public static IntegrationTestResult failed(String message) {
        return new IntegrationTestResult(false, message);
    }
}
