package com.hospio.common.integrations.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The field catalogue: matches each settings record, checks what is typed, masks secrets and lays saved values over the environment. */
class IntegrationCatalogueTest {

    @Test
    void everyProvidersFieldsAreItsRecordsComponentsInOrderAndOfTheRightType() {
        for (IntegrationProvider p : IntegrationProvider.values()) {
            RecordComponent[] cs = p.type().getRecordComponents();
            assertThat(cs).as(p.name()).hasSize(p.fields().size());
            for (int i = 0; i < cs.length; i++) {
                IntegrationField f = p.fields().get(i);
                assertThat(cs[i].getName()).as(p.name()).isEqualTo(f.key());
                Class<?> expected = f.type() == FieldType.NUMBER ? Integer.class : f.type() == FieldType.BOOLEAN ? Boolean.class : String.class;
                assertThat(cs[i].getType()).as(p + "." + f.key()).isEqualTo(expected);
                assertThat(f.env()).as(p + "." + f.key() + " names its environment variable").isNotBlank();
            }
            assertThat(IntegrationProvider.of(p.type())).isEqualTo(p);
            assertThat(p.group()).isIn("EMAIL", "MESSAGING", "PAYMENTS", "POS");
        }
        assertThat(IntegrationProvider.parse("termii")).contains(IntegrationProvider.TERMII);
        assertThat(IntegrationProvider.parse("fee-debit-opay")).as("the fee debit was removed on 19 Nov 2026").isEmpty();
        assertThat(IntegrationProvider.parse("nope")).isEmpty();
    }

    @Test
    void urlsNumbersSwitchesAndChoicesAreCheckedAndTidied() {
        IntegrationProvider infobip = IntegrationProvider.INFOBIP;
        assertThat(IntegrationCatalogue.normalize(infobip, infobip.field("baseUrl").orElseThrow(), " https://xyz.api.infobip.com/ "))
                .isEqualTo("https://xyz.api.infobip.com");
        assertThat(IntegrationCatalogue.normalize(infobip, infobip.field("smsEnabled").orElseThrow(), "TRUE")).isEqualTo("true");
        assertThat(IntegrationCatalogue.normalize(IntegrationProvider.SMTP, IntegrationProvider.SMTP.field("port").orElseThrow(), " 587 ")).isEqualTo("587");

        assertThatThrownBy(() -> IntegrationCatalogue.normalize(infobip, infobip.field("baseUrl").orElseThrow(), "xyz.api.infobip.com"))
                .isInstanceOf(BadRequestException.class).hasMessage("Base URL (Infobip (SMS and WhatsApp)) isn't a web address. Write it in full, e.g. https://api.example.com");
        assertThatThrownBy(() -> IntegrationCatalogue.normalize(infobip, infobip.field("notifyUrl").orElseThrow(), "ftp://x.y"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> IntegrationCatalogue.normalize(IntegrationProvider.SMTP, IntegrationProvider.SMTP.field("port").orElseThrow(), "five"))
                .hasMessage("Port (SMTP (email fallback)) must be a whole number from 1 to 65535");
        assertThatThrownBy(() -> IntegrationCatalogue.normalize(IntegrationProvider.SMTP, IntegrationProvider.SMTP.field("port").orElseThrow(), "70000"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> IntegrationCatalogue.normalize(infobip, infobip.field("smsEnabled").orElseThrow(), "yes"))
                .hasMessage("SMS on (Infobip (SMS and WhatsApp)) is on or off: send true or false");
    }

    @Test
    void secretsAreShownMaskedAndEverythingElseAsSaved() {
        IntegrationProvider brevo = IntegrationProvider.BREVO;
        assertThat(IntegrationCatalogue.display(brevo.field("apiKey").orElseThrow(), "xkeysib-abcdef123456")).isEqualTo("••••••3456");
        assertThat(IntegrationCatalogue.display(brevo.field("fromAddress").orElseThrow(), "no-reply@hospio360.com")).isEqualTo("no-reply@hospio360.com");
        assertThat(IntegrationCatalogue.display(brevo.field("apiKey").orElseThrow(), null)).isNull();
    }

    @Test
    void valuesAndRecordsRoundTripAndSerialiseAsTypedJson() throws Exception {
        Map<String, String> values = Map.of("host", "smtp-relay.brevo.com", "port", "2525", "enabled", "false");
        IntegrationSettings built = IntegrationCatalogue.build(IntegrationProvider.SMTP, values);
        assertThat(built).isEqualTo(new IntegrationSettings.Smtp("smtp-relay.brevo.com", 2525, null, null, false));
        assertThat(IntegrationCatalogue.values(built)).containsExactly(Map.entry("host", "smtp-relay.brevo.com"), Map.entry("port", "2525"),
                Map.entry("enabled", "false"));
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(built), IntegrationSettings.Smtp.class)).isEqualTo(built);
        assertThat(json.readValue("{\"host\":\"h\",\"later\":1}", IntegrationSettings.Smtp.class).host()).isEqualTo("h");
    }

    @Test
    void aSavedFieldWinsOverTheEnvironmentAndAnUnsavedOneFallsBack() {
        IntegrationSettings.Infobip env = new IntegrationSettings.Infobip("https://env.api.infobip.com", "env-key", "2348000000000", "Hospio360",
                null, null, true, true);
        IntegrationSettings.Infobip saved = new IntegrationSettings.Infobip(null, "portal-key", null, null, null, null, null, false);

        IntegrationSettings.Infobip in = IntegrationCatalogue.overlay(saved, env);

        assertThat(in).isEqualTo(new IntegrationSettings.Infobip("https://env.api.infobip.com", "portal-key", "2348000000000", "Hospio360",
                null, null, true, false));
        assertThat(IntegrationCatalogue.overlay(null, env)).isSameAs(env);
    }
}
