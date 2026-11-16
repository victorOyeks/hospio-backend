package com.hospio.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.security.SecretBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Keys saved masked and encrypted; a hotel without a provider (removed keys, or only a POS pause) reads as not connected. */
class HotelPosServiceTest {

    private final HotelPosConfigRepository repo = mock(HotelPosConfigRepository.class);
    private final HotelPosService service = new HotelPosService(repo, new SecretBox(Base64.getEncoder().encodeToString(new byte[32])), new ObjectMapper());

    @Test
    void aRowWithoutAProviderReadsAsNotConnected() {
        HotelPosConfig c = HotelPosConfig.builder().businessId("b1").hotelId("h1").posPaused(true).build();
        when(repo.findByHotelId("h1")).thenReturn(Optional.of(c));
        HotelPosService.View v = service.view("h1");
        assertThat(v.provider()).isNull();
        assertThat(v.fields()).isEmpty();
        assertThat(v.posPaused()).isTrue();
        assertThat(service.credentials("h1")).isEmpty();
    }

    @Test
    void savedKeysAreMaskedAndRemovedKeysAreGone() {
        HotelPosConfig[] row = {null};
        when(repo.findByHotelId("h1")).thenAnswer(i -> Optional.ofNullable(row[0]));
        when(repo.save(any())).thenAnswer(i -> row[0] = i.getArgument(0));
        HotelPosService.View v = service.save("b1", "h1", "moniepoint", Map.of("apiKey", "mp_test_abcdefgh12345678", "webhookSecret", "whsec_9876543210"));
        assertThat(v.complete()).isTrue();
        assertThat(v.fields().get("apiKey")).isEqualTo("••••••5678");
        assertThat(row[0].getSecrets()).startsWith("v1:").doesNotContain("mp_test");
        assertThat(service.credentials("h1")).get().extracting(HotelPosService.Credentials::provider).isEqualTo("MONIEPOINT");
        HotelPosService.View gone = service.remove("h1");
        assertThat(gone.provider()).isNull();
        assertThat(service.credentials("h1")).isEmpty();
    }
}
