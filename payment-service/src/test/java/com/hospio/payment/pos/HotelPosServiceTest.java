package com.hospio.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.SecretBox;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A hotel holds Moniepoint and OPay keys side by side, each encrypted and masked; removing one leaves the other. */
class HotelPosServiceTest {

    private final HotelPosConfigRepository configs = mock(HotelPosConfigRepository.class);
    private final HotelPosKeyRepository keys = mock(HotelPosKeyRepository.class);
    private final HotelPosService service = new HotelPosService(configs, keys, new SecretBox(Base64.getEncoder().encodeToString(new byte[32])), new ObjectMapper());
    private final List<HotelPosKey> rows = new ArrayList<>();

    @BeforeEach
    void store() {
        when(configs.findByHotelId(anyString())).thenReturn(Optional.empty());
        when(keys.findByHotelIdOrderByProvider(anyString())).thenAnswer(i -> rows.stream().filter(r -> r.getHotelId().equals(i.getArgument(0)))
                .sorted((a, b) -> a.getProvider().compareTo(b.getProvider())).toList());
        when(keys.findByHotelIdAndProvider(anyString(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getHotelId().equals(i.getArgument(0)) && r.getProvider().equals(i.getArgument(1))).findFirst());
        when(keys.findAll()).thenAnswer(i -> List.copyOf(rows));
        when(keys.save(any())).thenAnswer(i -> {
            HotelPosKey k = i.getArgument(0);
            rows.remove(k);
            rows.add(k);
            return k;
        });
        doAnswer(i -> rows.remove((HotelPosKey) i.getArgument(0))).when(keys).delete(any());
    }

    static String pkcs8() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        return Base64.getEncoder().encodeToString(g.generateKeyPair().getPrivate().getEncoded());
    }

    static String x509() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
    }

    @Test
    void bothProvidersAreKeptSideBySide() throws Exception {
        service.save("b1", "h1", "moniepoint", Map.of("apiKey", "mp_test_abcdefgh12345678", "webhookSecret", "whsec_9876543210"));
        HotelPosService.View v = service.save("b1", "h1", "OPAY", Map.of("clientAuthKey", "cak_1234567890", "opayPublicKey", x509(),
                "merchantPrivateKey", pkcs8(), "headMerchantId", "256625100332195", "merchantId", "256626080518047"));
        assertThat(v.providers()).extracting(HotelPosService.ProviderKeys::provider).containsExactly("MONIEPOINT", "OPAY");
        assertThat(v.providers()).allMatch(HotelPosService.ProviderKeys::complete);
        assertThat(v.providers().get(0).fields().get("apiKey")).isEqualTo("••••••5678");
        assertThat(rows).allMatch(r -> r.getSecrets().startsWith("v1:") && !r.getSecrets().contains("mp_test"));
        // Each payment uses its terminal's provider (any case).
        assertThat(service.credentials("h1", "moniepoint")).get().extracting(HotelPosService.Credentials::provider).isEqualTo("MONIEPOINT");
        assertThat(service.credentials("h1", "OPAY")).get().extracting(c -> c.values().get("merchantId")).isEqualTo("256626080518047");
        assertThat(service.credentials("h1", "logging")).isEmpty();
        assertThat(service.providersWithKeys("h1")).containsExactly("MONIEPOINT", "OPAY");
        assertThat(service.statuses()).singleElement().satisfies(s -> assertThat(s.providers()).containsExactly("MONIEPOINT", "OPAY"));
    }

    @Test
    void removingOneProviderKeepsTheOther() {
        service.save("b1", "h1", "MONIEPOINT", Map.of("apiKey", "mp_test_abcdefgh12345678", "webhookSecret", "whsec_9876543210"));
        service.save("b1", "h1", "OPAY", Map.of("clientAuthKey", "cak_1234567890"));
        HotelPosService.View v = service.remove("h1", "opay");
        assertThat(v.providers()).extracting(HotelPosService.ProviderKeys::provider).containsExactly("MONIEPOINT");
        assertThat(service.credentials("h1", "MONIEPOINT")).isPresent();
        assertThat(service.credentials("h1", "OPAY")).isEmpty();
    }

    @Test
    void aBlankFieldKeepsItsSavedValue() {
        service.save("b1", "h1", "MONIEPOINT", Map.of("apiKey", "mp_test_abcdefgh12345678", "webhookSecret", "whsec_9876543210"));
        service.save("b1", "h1", "MONIEPOINT", Map.of("webhookSecret", "whsec_new_000011112222"));
        assertThat(service.credentials("h1", "MONIEPOINT")).get().satisfies(c -> {
            assertThat(c.values().get("apiKey")).isEqualTo("mp_test_abcdefgh12345678");
            assertThat(c.values().get("webhookSecret")).isEqualTo("whsec_new_000011112222");
        });
    }

    @Test
    void anUnknownProviderOrNoKeysIsRefused() {
        assertThatThrownBy(() -> service.save("b1", "h1", "STRIPE", Map.of("apiKey", "x"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save("b1", "h1", "MONIEPOINT", Map.of())).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least one key");
    }
}
