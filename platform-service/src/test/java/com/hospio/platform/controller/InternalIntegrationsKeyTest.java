package com.hospio.platform.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hospio.common.config.SecurityChains;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.platform.controller.internal.InternalPlatformController;
import com.hospio.platform.repository.ApprovalRepository;
import com.hospio.platform.service.IntegrationSettingsService;
import com.hospio.platform.service.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /internal/integrations/{provider} hands out decrypted keys, so it answers only with the internal API key. */
class InternalIntegrationsKeyTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IntegrationSettingsService integrations = mock(IntegrationSettingsService.class);
        when(integrations.saved("PAYSTACK")).thenReturn(new IntegrationSettings.Paystack("sk_live_secret"));
        InternalPlatformController controller = new InternalPlatformController(mock(ApprovalRepository.class), mock(PlatformSettingsService.class),
                integrations, mock(com.hospio.platform.service.MarketerService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(SecurityChains.internalFilter("the-internal-key")).build();
    }

    @Test
    void withoutTheInternalKeyItIsRefused() throws Exception {
        mvc.perform(get("/internal/integrations/PAYSTACK").servletPath("/internal/integrations/PAYSTACK"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/integrations/PAYSTACK").servletPath("/internal/integrations/PAYSTACK").header("X-Internal-Api-Key", "a-guess"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withTheInternalKeyTheServiceGetsTheTypedRecord() throws Exception {
        mvc.perform(get("/internal/integrations/PAYSTACK").servletPath("/internal/integrations/PAYSTACK").header("X-Internal-Api-Key", "the-internal-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secretKey").value("sk_live_secret"));
    }
}
