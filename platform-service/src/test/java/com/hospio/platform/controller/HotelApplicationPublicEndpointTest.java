package com.hospio.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hospio.platform.dto.HotelApplicationDtos.Submission;
import com.hospio.platform.service.HotelApplicationService;
import com.hospio.platform.service.SiteCallers;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** POST /public/hotel-applications: 200 "received", 400 with a message per field, 429 with Retry-After. */
class HotelApplicationPublicEndpointTest {

    private final HotelApplicationService service = mock(HotelApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(service.supportEmail()).thenReturn("hello@oyeksglobal.com");
        mvc = MockMvcBuilders.standaloneSetup(new HotelApplicationController(service, new SiteCallers(""))).build();
    }

    @Test
    void aReceivedRequestGetsTheOneWorkingDaySentence() throws Exception {
        when(service.submit(any(), eq("127.0.0.1"), any())).thenReturn(Submission.received());
        mvc.perform(post("/public/hotel-applications").contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"Bola Ade\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Thanks — we've received your request. Our team will call you within 1 working day."))
                .andExpect(jsonPath("$.data.received").value(true));
    }

    @Test
    void anInvalidRequestGets400WithAMessagePerField() throws Exception {
        when(service.submit(any(), anyString(), any())).thenReturn(new Submission(Submission.INVALID,
                Map.of("phone", "Enter a Nigerian phone number, like 0803 555 0192 or +234 803 555 0192")));
        mvc.perform(post("/public/hotel-applications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.phone").value("Enter a Nigerian phone number, like 0803 555 0192 or +234 803 555 0192"));
    }

    @Test
    void overTheHoursLimitGets429() throws Exception {
        when(service.submit(any(), anyString(), any())).thenReturn(new Submission(Submission.LIMITED, Map.of()));
        mvc.perform(post("/public/hotel-applications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("hello@oyeksglobal.com")));
    }
}
