package com.hospio.common.http;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one place every outbound HTTP client gets its timeouts from. A plain RestClient has no connect
 * or read timeout, and one hung call to a third party parks a thread for good.
 */
public final class HttpTimeouts {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private HttpTimeouts() {
    }

    public static ClientHttpRequestFactory requestFactory() {
        return requestFactory(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout));
    }

    public static RestClient.Builder builder() {
        return RestClient.builder().requestFactory(requestFactory());
    }

    public static RestClient.Builder builder(Duration connectTimeout, Duration readTimeout) {
        return RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout));
    }

    public static RestClient create(String baseUrl) {
        return builder().baseUrl(baseUrl).build();
    }
}
