package com.hospio.common.integrations;

import java.util.Optional;

/** Receipts, handover packs, reports and photos. Keys are {@code <service>/<business>/<yyyy>/<file>}. */
public interface ObjectStorage {

    String put(String key, byte[] content, String contentType);

    Optional<byte[]> get(String key);

    /** A URL a browser can open for a short while, or null when the service has to proxy the bytes. */
    String url(String key);
}
