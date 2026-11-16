package com.hospio.messaging;

import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.client.RestClient;

/** What the admin portal has saved, as the clients see it through the reader; nothing saved until a test saves something. */
public final class StubIntegrations extends IntegrationSettingsReader {

    private final Map<Class<?>, IntegrationSettings> saved = new ConcurrentHashMap<>();

    public StubIntegrations() {
        super(RestClient.builder().baseUrl("http://127.0.0.1:1").build());
    }

    public StubIntegrations save(IntegrationSettings s) {
        saved.put(s.getClass(), s);
        return this;
    }

    @Override
    public <T extends IntegrationSettings> Optional<T> saved(Class<T> type) {
        return Optional.ofNullable(type.cast(saved.get(type)));
    }

    @Override
    public <T extends IntegrationSettings> Optional<T> fresh(Class<T> type) {
        return saved(type);
    }
}
