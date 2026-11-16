package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.tenant.dto.OnboardingDraftData;
import com.hospio.tenant.dto.OnboardingLogin;
import com.hospio.tenant.dto.OnboardingStepData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The onboarding draft: each step's form kept under its number with the same JSON the wizards sent, completed steps in order,
 * the next action, and the logins gathered for the handover pack.
 */
class DraftDataTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DraftData drafts = new DraftData(mapper);

    private OnboardingStepData step(String json) throws Exception {
        return mapper.readValue(json, OnboardingStepData.class);
    }

    @Test
    void aNewDraftIsStoredAsBefore() {
        assertThat(drafts.empty()).isEqualTo("{\"steps\":{}}");
        assertThat(drafts.completedSteps(drafts.empty())).isEmpty();
        assertThat(drafts.parse(null).steps()).isNull();
        assertThat(drafts.parse("not json").completedSteps()).isNull();
    }

    @Test
    void everyStepsFormGoesBackOutExactlyAsTheWizardSentIt() throws Exception {
        List<String> forms = List.of(
                "{\"name\":\"Royal Suites\",\"legalName\":\"Royal Suites Ltd\",\"businessType\":\"HOTEL\",\"address\":\"12 Allen\",\"city\":\"Lagos\",\"country\":\"NG\",\"timezone\":\"Africa/Lagos\"}",
                "{\"ownerName\":\"Godwin Eze\",\"ownerEmail\":\"g@r.ng\",\"ownerPhone\":\"08031110001\",\"contactName\":\"Hauwa\",\"contactEmail\":\"h@r.ng\"}",
                "{\"feeModel\":\"PERCENT_OF_PAYMENTS\",\"feeValue\":2.5,\"currency\":\"NGN\",\"contractStartsOn\":\"2026-11-01\",\"marketerId\":\"m1\"}",
                "{\"hotelIds\":[\"h1\",\"h2\"]}",
                "{\"byHotel\":{\"h1\":{\"roomTypes\":[{\"roomTypeId\":\"t1\",\"name\":\"Deluxe\",\"price\":45000,\"count\":10}],\"created\":10}}}",
                "{\"templateId\":\"tpl\",\"appliedTo\":[\"h1\"],\"picked\":[\"h1\",\"h2\"],\"appliedAt\":{\"h1\":\"2026-11-16T10:00:00.000Z\"}}",
                "{\"logins\":[{\"userId\":\"u1\",\"fullName\":\"Tunde\",\"email\":\"t@r.ng\",\"role\":\"MANAGER\",\"hotelId\":\"h1\",\"hotelName\":\"Ikeja\",\"temporaryPassword\":\"S3cret\",\"temporaryPasswordExpiresAt\":\"2026-11-19T10:00:00.000Z\",\"inviteToken\":\"tok\",\"inviteExpiresAt\":\"2026-11-23T10:00:00.000Z\"}]}",
                "{\"terminals\":[{\"hotelId\":\"h1\",\"terminalId\":\"pt1\",\"name\":\"Front desk\",\"provider\":\"opay\",\"terminalRef\":\"T-1\"}],\"whatsappNumber\":\"0803\",\"whatsappByHotel\":{\"h1\":\"0803\"}}",
                "{\"checks\":[{\"key\":\"rooms\",\"label\":\"Rooms\",\"done\":true,\"detail\":\"10 rooms\"}],\"notes\":\"ok\",\"readyAt\":\"2026-11-16T10:00:00.000Z\"}",
                "{\"ownerEmail\":\"g@r.ng\"}",
                // self-serve steps 2-4
                "{\"hotelId\":\"h1\",\"name\":\"Simpas Ikeja\",\"city\":\"Lagos\"}",
                "{\"roomTypes\":[{\"roomTypeId\":\"t1\",\"name\":\"Standard\",\"price\":30000.5,\"count\":4}],\"created\":4}",
                "{\"userId\":\"u2\",\"fullName\":\"Ada\",\"email\":\"a@r.ng\",\"temporaryPassword\":\"pw\",\"inviteToken\":\"it\"}",
                "{}");
        for (String form : forms) {
            assertThat(mapper.readValue(mapper.writeValueAsString(step(form)), Map.class)).as(form).isEqualTo(mapper.readValue(form, Map.class));
        }
    }

    @Test
    void savingStepsKeepsEachUnderItsNumberAndListsTheLogins() throws Exception {
        OnboardingDraftData data = drafts.parse(drafts.empty());
        data = drafts.saveStep(data, 2, step("{\"ownerName\":\"Godwin\"}"), null);
        data = drafts.saveStep(data, 7, step("{\"logins\":[{\"userId\":\"u1\",\"email\":\"t@r.ng\",\"role\":\"MANAGER\"}]}"), "Connect terminals");
        data = drafts.saveStep(data, 1, null, null);
        data = drafts.addLogin(data, OnboardingLogin.owner("u0", "Godwin", "g@r.ng"));

        OnboardingDraftData back = drafts.parse(drafts.write(data));
        assertThat(back.completedSteps()).containsExactly(1, 2, 7);
        assertThat(back.nextAction()).isEqualTo("Connect terminals");
        assertThat(back.steps()).containsKeys("1", "2", "7");
        assertThat(back.steps().get("2").ownerName()).isEqualTo("Godwin");
        assertThat(drafts.logins(back)).extracting(OnboardingLogin::userId).containsExactly("u0", "u1");
        assertThat(drafts.write(back)).contains("\"1\":{}").contains("\"logins\":[{\"userId\":\"u0\",\"fullName\":\"Godwin\",\"email\":\"g@r.ng\",\"role\":\"OWNER\"}]");
    }

    @Test
    void aDraftWrittenByTheOldCodeIsReadAsItIs() {
        String old = "{\"steps\":{\"1\":{\"name\":\"Royal\"},\"2\":{\"ownerName\":\"G\",\"contactPhone\":\"0803\"}},\"completedSteps\":[2,1],"
                + "\"nextAction\":\"Fee\",\"logins\":[{\"userId\":\"u0\",\"fullName\":\"G\",\"email\":\"g@r.ng\",\"role\":\"OWNER\",\"hotelId\":null}]}";
        OnboardingDraftData data = drafts.parse(old);
        assertThat(drafts.completedSteps(data)).containsExactly(1, 2);
        assertThat(data.steps().get("2").contactPhone()).isEqualTo("0803");
        assertThat(data.logins()).containsExactly(OnboardingLogin.owner("u0", "G", "g@r.ng"));
        assertThat(Map.copyOf(data.steps())).hasSize(2);
    }
}
