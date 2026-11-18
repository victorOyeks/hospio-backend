package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.kafka.events.IntegrationChangedEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.SecretBox;
import com.hospio.platform.dto.IntegrationDtos.FieldView;
import com.hospio.platform.dto.IntegrationDtos.IntegrationUpdate;
import com.hospio.platform.dto.IntegrationDtos.IntegrationView;
import com.hospio.platform.entity.IntegrationSetting;
import com.hospio.platform.repository.IntegrationSettingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settings · Integrations: what is saved, kept and cleared; checks and masking; encrypted at rest; the activity log names fields, never
 * values; and the decrypted record the services read.
 */
class IntegrationSettingsServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final Map<String, IntegrationSetting> table = new HashMap<>();
    private final IntegrationSettingRepository rows = mock(IntegrationSettingRepository.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private IntegrationSettingsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(rows.findByProvider(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.getArgument(0, String.class))));
        when(rows.findAll()).thenAnswer(i -> new ArrayList<>(table.values()));
        when(rows.save(any())).thenAnswer(i -> {
            IntegrationSetting r = i.getArgument(0);
            r.setUpdatedAt(Instant.parse("2026-11-16T09:30:00Z"));
            table.put(r.getProvider(), r);
            return r;
        });
        org.mockito.Mockito.doAnswer(i -> table.remove(i.getArgument(0, IntegrationSetting.class).getProvider())).when(rows).delete(any());
        when(tx.execute(any())).thenAnswer(i -> i.getArgument(0, TransactionCallback.class).doInTransaction(null));
        service = new IntegrationSettingsService(rows, new SecretBox(KEY), new ObjectMapper(), activity, tx);
        HospioUserDetails admin = new HospioUserDetails("u-admin", "kemi@hospio360.com", "Kemi Adeyemi", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static IntegrationUpdate fields(Map<String, String> fields) {
        return new IntegrationUpdate(fields, null);
    }

    private static FieldView field(IntegrationView v, String key) {
        return v.fields().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void theListHasEveryProviderAndNothingSavedUntilSomeoneSaves() {
        List<IntegrationView> all = service.list();

        assertThat(all).extracting(IntegrationView::provider).containsExactly("BREVO", "SMTP", "INFOBIP", "TERMII", "PAYSTACK", "MONIEPOINT",
                "OPAY", "FEE_DEBIT_OPAY", "FEE_DEBIT_MONIEPOINT");
        IntegrationView brevo = all.get(0);
        assertThat(brevo.group()).isEqualTo("EMAIL");
        assertThat(brevo.testable()).isTrue();
        assertThat(brevo.updatedAt()).isNull();
        assertThat(brevo.fields()).allSatisfy(f -> {
            assertThat(f.saved()).isFalse();
            assertThat(f.value()).isNull();
        });
        assertThat(field(brevo, "apiKey").type()).isEqualTo("SECRET");
        assertThat(field(brevo, "apiKey").hint()).endsWith("Empty: the server's BREVO_API_KEY applies.");
        assertThat(all.get(7).testable()).as("the fee debit would move money").isFalse();
    }

    @Test
    void aSaveIsEncryptedAtRestAndShownMasked() {
        IntegrationView v = service.update("brevo", fields(Map.of("apiKey", " xkeysib-abcdef123456 ", "fromAddress", "no-reply@hospio360.com")));

        assertThat(field(v, "apiKey").value()).isEqualTo("••••••3456");
        assertThat(field(v, "apiKey").saved()).isTrue();
        assertThat(field(v, "fromAddress").value()).isEqualTo("no-reply@hospio360.com");
        assertThat(field(v, "fromName").saved()).isFalse();
        assertThat(v.updatedByName()).isEqualTo("Kemi Adeyemi");
        assertThat(v.updatedAt()).isEqualTo("2026-11-16T09:30:00Z");

        IntegrationSetting row = table.get("BREVO");
        assertThat(row.getSealedValues()).startsWith("v1:").doesNotContain("xkeysib");
        assertThat(row.getMaskedValues()).doesNotContain("xkeysib");
        assertThat(service.saved("BREVO")).isEqualTo(new IntegrationSettings.Brevo("xkeysib-abcdef123456", "no-reply@hospio360.com", null));
        assertThat(service.list().get(0).fields().get(0).value()).isEqualTo("••••••3456");
    }

    @Test
    void theScreenMaySendSwitchesAndNumbersAsJsonBooleansAndNumbers() throws Exception {
        IntegrationUpdate body = new ObjectMapper().readValue("{\"fields\":{\"enabled\":true,\"port\":2525,\"host\":\"smtp-relay.brevo.com\"}}",
                IntegrationUpdate.class);

        IntegrationView v = service.update("SMTP", body);

        assertThat(field(v, "enabled").value()).isEqualTo("true");
        assertThat(field(v, "port").value()).isEqualTo("2525");
        assertThat(service.saved("SMTP")).isEqualTo(new IntegrationSettings.Smtp("smtp-relay.brevo.com", 2525, null, null, true));
    }

    @Test
    void aBlankValueKeepsWhatIsSavedAndClearHandsTheFieldBackToTheServer() {
        service.update("INFOBIP", fields(Map.of("baseUrl", "https://xyz.api.infobip.com/", "apiKey", "infobip-key-123456", "smsEnabled", "true")));

        Map<String, String> keep = new LinkedHashMap<>();
        keep.put("apiKey", "");
        keep.put("baseUrl", null);
        keep.put("whatsappSender", "+2348148849797");
        IntegrationView v = service.update("INFOBIP", new IntegrationUpdate(keep, List.of("smsEnabled")));

        assertThat(field(v, "apiKey").value()).isEqualTo("••••••3456");
        assertThat(field(v, "baseUrl").value()).isEqualTo("https://xyz.api.infobip.com");
        assertThat(field(v, "smsEnabled").saved()).isFalse();
        assertThat(service.saved("INFOBIP")).isEqualTo(new IntegrationSettings.Infobip("https://xyz.api.infobip.com", "infobip-key-123456",
                "+2348148849797", null, null, null, null, null));
    }

    @Test
    void clearingEveryFieldRemovesTheRowSoTheServerAppliesAgain() {
        service.update("PAYSTACK", fields(Map.of("secretKey", "sk_live_abcdef123456")));

        IntegrationView v = service.update("PAYSTACK", new IntegrationUpdate(null, List.of("secretKey")));

        assertThat(table).doesNotContainKey("PAYSTACK");
        assertThat(field(v, "secretKey").saved()).isFalse();
        assertThat(v.updatedAt()).isNull();
        assertThat(service.saved("PAYSTACK")).isNull();
    }

    @Test
    void theActivityLogNamesTheFieldsAndNeverTheValues() {
        service.update("TERMII", fields(Map.of("apiKey", "TL-secret-value-1234", "senderId", "Hospio360")));
        ArgumentCaptor<IntegrationChangedEvent> saved = ArgumentCaptor.forClass(IntegrationChangedEvent.class);
        verify(activity).record(eq(IntegrationSettingsService.TOPIC), saved.capture());
        IntegrationChangedEvent e = saved.getValue();
        assertThat(e.getSaved()).containsExactly("apiKey", "senderId");
        assertThat(e.getActorName()).isEqualTo("Hospio360 · Kemi Adeyemi");
        assertThat(e.getBusinessId()).isNull();
        assertThat(e.summary()).isEqualTo("Settings · Integrations · Termii (SMS sign-in codes): apiKey, senderId saved");
        assertThat(e.toString()).doesNotContain("TL-secret-value-1234");

        service.update("TERMII", new IntegrationUpdate(Map.of("apiKey", "TL-secret-value-1234"), List.of("senderId")));
        ArgumentCaptor<IntegrationChangedEvent> again = ArgumentCaptor.forClass(IntegrationChangedEvent.class);
        verify(activity, org.mockito.Mockito.times(2)).record(eq(IntegrationSettingsService.TOPIC), again.capture());
        assertThat(again.getValue().getSaved()).as("the same key again is not a change").isEmpty();
        assertThat(again.getValue().getCleared()).containsExactly("senderId");
    }

    @Test
    void savingNothingNewChangesNothingAndLogsNothing() {
        service.update("OPAY", fields(Map.of("baseUrl", " ")));
        assertThat(table).isEmpty();
        verify(activity, never()).record(anyString(), any());
    }

    @Test
    void badInputIsRefusedInPlainSentences() {
        assertThatThrownBy(() -> service.update("SMS", fields(Map.of())))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageStartingWith("There is no integration called SMS.");
        assertThatThrownBy(() -> service.update("BREVO", fields(Map.of("password", "x"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Brevo (email API) has no field called password. Its fields are: apiKey, fromAddress, fromName, webhookKey");
        assertThatThrownBy(() -> service.update("SMTP", fields(Map.of("port", "smtp"))))
                .hasMessage("Port (SMTP (email fallback)) must be a whole number from 1 to 65535");
        assertThatThrownBy(() -> service.update("MONIEPOINT", fields(Map.of("baseUrl", "moniepoint"))))
                .hasMessageContaining("isn't a web address");
        assertThatThrownBy(() -> service.update("FEE_DEBIT_OPAY", fields(Map.of("payoutType", "CASH"))))
                .hasMessage("Payout type (Daily fee debit: OPay) must be one of: OPAY_WALLET, BANK_TRANSFER");
        assertThatThrownBy(() -> service.update("PAYSTACK", new IntegrationUpdate(Map.of("secretKey", "sk_1"), List.of("secretKey"))))
                .hasMessage("Secret key can't be saved and cleared at once. Send it in one or the other");
        assertThat(table).isEmpty();
    }

    @Test
    void withoutAMasterKeyNothingCanBeSavedButTheListStillShows() {
        IntegrationSettingsService noKey = new IntegrationSettingsService(rows, new SecretBox(""), new ObjectMapper(), activity, tx);
        assertThatThrownBy(() -> noKey.update("PAYSTACK", fields(Map.of("secretKey", "sk_live_1"))))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("SECRETS_KEY isn't set on the server");
        assertThat(noKey.list()).hasSize(9);
    }

    @Test
    void settingsSealedWithAnotherKeyCanOnlyBeClearedCompletely() {
        service.update("PAYSTACK", fields(Map.of("secretKey", "sk_live_abcdef123456")));
        byte[] other = new byte[32];
        other[0] = 1;
        IntegrationSettingsService rotated = new IntegrationSettingsService(rows, new SecretBox(Base64.getEncoder().encodeToString(other)),
                new ObjectMapper(), activity, tx);

        assertThatThrownBy(() -> rotated.saved("PAYSTACK")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rotated.update("PAYSTACK", fields(Map.of("secretKey", "sk_live_new")))).hasMessageContaining("Clear every field");
        rotated.update("PAYSTACK", new IntegrationUpdate(null, List.of("secretKey")));
        assertThat(table).isEmpty();
    }
}
