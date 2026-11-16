package com.hospio.hotelconfig.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hospio.common.setup.HotelSetup;
import com.hospio.common.setup.SetupSection;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.dto.ApplyTemplateRequest;
import com.hospio.hotelconfig.service.HotelSetupService;
import com.hospio.hotelconfig.service.ListsAndPricesService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The web apps' wire shape: GET /setup is {SECTION: {key: value}}, a section is {key: value}, PUT takes the section's keys. */
class HotelSetupControllerTest {

    private final HotelSetupService setup = mock(HotelSetupService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HotelSetupController(setup, mock(ListsAndPricesService.class))).build();

    @Test
    void everySectionIsKeyedByItsNameWithItsKeys() throws Exception {
        when(setup.all("h1")).thenReturn(HotelSetup.DEFAULTS);
        TenantContext.runWith(Tenant.systemFor("b1", "h1", null, null), () -> {
            try {
                mvc.perform(get("/setup")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.BASICS.checkInTime").value("14:00"))
                        .andExpect(jsonPath("$.data.PAYMENTS.depositPct").value(0))
                        .andExpect(jsonPath("$.data.TAXES_CURRENCY.vatPct").value(7.5))
                        .andExpect(jsonPath("$.data.BRAND.darkSidebar").value(true))
                        .andExpect(jsonPath("$.data.NIGHT_AUDIT.closeTime").value("23:00"))
                        .andExpect(jsonPath("$.data.POS.provider").value("logging"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Test
    void aSectionGoesOutAsItsKeysAndASaveSendsTheBodyOn() throws Exception {
        when(setup.section("payments")).thenReturn(SetupSection.Payments.DEFAULTS);
        mvc.perform(get("/setup/payments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipts").value("WHATSAPP"))
                .andExpect(jsonPath("$.data.allowTransfer").value(true));

        String body = "{\"depositPct\":20,\"allowCash\":false}";
        when(setup.save("PAYMENTS", body)).thenReturn(new SetupSection.Payments(false, true, true, "WHATSAPP", BigDecimal.valueOf(20),
                BigDecimal.ZERO, "", ""));
        mvc.perform(put("/setup/PAYMENTS").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Saved"))
                .andExpect(jsonPath("$.data.depositPct").value(20))
                .andExpect(jsonPath("$.data.allowCash").value(false));
    }

    @Test
    void anInlineTemplateIsReadTyped() throws Exception {
        when(setup.applyTemplate(any())).thenReturn(HotelSetup.DEFAULTS);
        mvc.perform(post("/setup/apply-template").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":null,\"settings\":{\"sections\":{\"STAFF\":{\"lateMinutes\":5}},\"lists\":{\"SHIFTS\":[\"Day\"]}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.STAFF.lateMinutes").value(15));
        ArgumentCaptor<ApplyTemplateRequest> sent = ArgumentCaptor.forClass(ApplyTemplateRequest.class);
        verify(setup).applyTemplate(sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().settings().sectionsToApply().staff().lateMinutes()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(sent.getValue().settings().listsToApply().get("SHIFTS").get(0).label()).isEqualTo("Day");
        verify(setup).applyTemplate(eq(sent.getValue()));
    }
}
