package com.hospio.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.billing.FeeDebitDtos.ProviderState;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.security.SecretBox;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Settings · Integrations in payment-service: the POS addresses and the fee debit use the environment until something is saved in
 * the portal, then the portal's value; a POS key sealed before SecretBox moved to common still opens with POS_SECRETS_KEY.
 */
class PortalIntegrationSettingsTest {

    /** What the portal has saved, nothing until a test saves something; platform-service is never called. */
    static final class Portal extends IntegrationSettingsReader {
        private final Map<Class<?>, IntegrationSettings> saved = new ConcurrentHashMap<>();

        Portal() {
            super(RestClient.builder().baseUrl("http://127.0.0.1:1").build());
        }

        Portal save(IntegrationSettings s) {
            saved.put(s.getClass(), s);
            return this;
        }

        @Override
        public <T extends IntegrationSettings> Optional<T> saved(Class<T> type) {
            return Optional.ofNullable(type.cast(saved.get(type)));
        }
    }

    /** Bytes 0..31, base64: the key the fixture below was sealed with. */
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    /** A hotel's Moniepoint keys sealed by payment-service's own SecretBox before the move (97d0b15). */
    private static final String SEALED_BEFORE_THE_MOVE = "v1:MsO+Z4f0+gPQNtl8ItmFOobyj8+oT7NWNUC+cBErJSX6eMNWaX+xCOqxtVcPvUJ97DnWYEbBFZpLFIYo4XUDiJKdTdcOLlh0oxbVYaBGgFnFmS3RYyK5VLPDN4Ddvw0=";

    @Test
    void aHotelsPosKeysSealedBeforeTheMoveStillOpen() {
        HotelPosConfigRepository repo = mock(HotelPosConfigRepository.class);
        when(repo.findByHotelId("h1")).thenReturn(Optional.of(HotelPosConfig.builder().businessId("b1").hotelId("h1").provider("MONIEPOINT")
                .secrets(SEALED_BEFORE_THE_MOVE).build()));
        HotelPosService service = new HotelPosService(repo, new SecretBox(KEY), new ObjectMapper());

        HotelPosService.Credentials c = service.credentials("h1").orElseThrow();

        assertThat(c.moniepoint()).isEqualTo(new MoniepointApi.Keys("mp_live_abcdef123456", "whsec_staging_1"));
    }

    @Test
    void theMasterKeyIsSecretsKeyElsePosSecretsKey() throws Exception {
        assertThat(resolve(Map.of("POS_SECRETS_KEY", KEY))).isEqualTo(KEY);
        assertThat(resolve(Map.of("SECRETS_KEY", "new-key", "POS_SECRETS_KEY", KEY))).isEqualTo("new-key");
        assertThat(resolve(Map.of())).isEmpty();
    }

    private static String resolve(Map<String, Object> env) throws Exception {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        e.getPropertySources().addFirst(new MapPropertySource("env", env));
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")).forEach(e.getPropertySources()::addLast);
        return e.getProperty("hospio.secrets-key");
    }

    @Test
    void posAddressesComeFromThePortalOnceSavedElseTheEnvironment() {
        Portal portal = new Portal();
        MoniepointApi moniepoint = new MoniepointApi("");
        moniepoint.setIntegrations(portal);
        OpayApi opay = new OpayApi(new ObjectMapper(), "https://payapi.opayweb.com/openApi");
        opay.setIntegrations(portal);
        assertThat(moniepoint.configured()).as("MONIEPOINT_BASE_URL isn't set").isFalse();
        assertThat(opay.baseUrl()).isEqualTo("https://payapi.opayweb.com/openApi");

        portal.save(new IntegrationSettings.Moniepoint("https://api.moniepoint.example")).save(new IntegrationSettings.Opay("https://sandbox.opay.example/openApi"));

        assertThat(moniepoint.configured()).isTrue();
        assertThat(moniepoint.baseUrl()).isEqualTo("https://api.moniepoint.example");
        assertThat(opay.baseUrl()).isEqualTo("https://sandbox.opay.example/openApi");
    }

    @Test
    void theFeeDebitUsesTheEnvironmentUntilThePortalSwitchesItOn() {
        HotelPosService hotels = mock(HotelPosService.class);
        when(hotels.credentials("h1")).thenReturn(Optional.empty());
        FeeDebitService fees = new FeeDebitService(mock(FeeDebitRepository.class), hotels, mock(OpayApi.class), mock(TransactionTemplate.class),
                false, "", "", "", "OPAY_WALLET", "");
        Portal portal = new Portal();
        fees.setIntegrations(portal);
        assertThat(fees.providers("h1")).extracting(ProviderState::reason)
                .containsExactly("Moniepoint hasn't given a debit API yet (FEE_DEBIT_MONIEPOINT_URL isn't set)", "FEE_DEBIT_OPAY_ENABLED isn't on");

        portal.save(new IntegrationSettings.FeeDebitOpay(true, "1234567890", "Hospio360 Ltd", null, "BANK_TRANSFER"));
        List<ProviderState> states = fees.providers("h1");
        assertThat(states.get(1).reason()).isEqualTo("FEE_DEBIT_OPAY_RECIPIENT_BANK_CODE isn't set");

        portal.save(new IntegrationSettings.FeeDebitOpay(true, "1234567890", "Hospio360 Ltd", "100004", "BANK_TRANSFER"));
        assertThat(fees.providers("h1").get(1).debitConfigured()).isTrue();
    }
}
