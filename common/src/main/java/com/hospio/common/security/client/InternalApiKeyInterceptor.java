package com.hospio.common.security.client;

import com.hospio.common.security.filter.InternalApiKeyFilter;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Attaches the shared internal API key to every outgoing call on the RestClient it is registered on. */
public class InternalApiKeyInterceptor implements ClientHttpRequestInterceptor {

    private final String apiKey;

    public InternalApiKeyInterceptor(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().add(InternalApiKeyFilter.HEADER, apiKey);
        return execution.execute(request, body);
    }
}
