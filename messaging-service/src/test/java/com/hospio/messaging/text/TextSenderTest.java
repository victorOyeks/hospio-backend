package com.hospio.messaging.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** docs/sms-whatsapp.md: WhatsApp only as an approved template, otherwise a one-page SMS; staging texts only its allow-list. */
class TextSenderTest {

    private final InfobipClient infobip = mock(InfobipClient.class);

    @BeforeEach
    void setUp() {
        when(infobip.configured()).thenReturn(true);
        when(infobip.whatsAppConfigured()).thenReturn(true);
        when(infobip.sms(anyString(), anyString())).thenReturn(new InfobipClient.Sent(true, "s1", null));
        when(infobip.whatsAppTemplate(anyString(), anyString(), anyString(), any(), anyBoolean())).thenReturn(new InfobipClient.Sent(true, "w1", null));
    }

    private TextSender sender(String allowOnly) {
        return new TextSender(infobip, true, true, "en", allowOnly);
    }

    @Test
    void anApprovedTemplateGoesOnWhatsApp() {
        when(infobip.templates()).thenReturn(Map.of("hospio_booking_changed", new InfobipClient.TemplateInfo("APPROVED", "UTILITY", "en")));
        TextSender.Outcome o = sender("").send("WHATSAPP", "08031110001", "fallback", "hospio_booking_changed", List.of("Ada", "BK-1", "Royal", "a", "b"));
        assertThat(o.sent()).isTrue();
        assertThat(o.channel()).isEqualTo("WHATSAPP");
        verify(infobip).whatsAppTemplate(eq("2348031110001"), eq("hospio_booking_changed"), eq("en"), any(), eq(false));
        verify(infobip, never()).sms(anyString(), anyString());
    }

    @Test
    void aTemplateStillInReviewGoesAsAOnePageSms() {
        when(infobip.templates()).thenReturn(Map.of("hospio_booking_changed", new InfobipClient.TemplateInfo("PENDING", "UTILITY", "en")));
        String longText = "Royal Suites · Hello Ada, booking BK-1 is now Sat 21 Nov to Tue 24 Nov. ".repeat(4);
        TextSender.Outcome o = sender("").send("WHATSAPP", "+234 803 111 0001", longText, "hospio_booking_changed", List.of());
        assertThat(o.sent()).isTrue();
        assertThat(o.channel()).isEqualTo("SMS");
        assertThat(o.note()).contains("pending");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(infobip).sms(eq("2348031110001"), text.capture());
        assertThat(text.getValue().length()).isLessThanOrEqualTo(160);
        assertThat(text.getValue()).doesNotContain("·");
    }

    @Test
    void aCodeTemplateCarriesTheCodeOnItsButton() {
        when(infobip.templates()).thenReturn(Map.of("oyeks_otp", new InfobipClient.TemplateInfo("APPROVED", "AUTHENTICATION", "en")));
        sender("").send("WHATSAPP", "2348031110001", "Your code is 123456", "oyeks_otp", List.of("123456"));
        verify(infobip).whatsAppTemplate(eq("2348031110001"), eq("oyeks_otp"), eq("en"), eq(List.of("123456")), eq(true));
    }

    @Test
    void numbersOffTheAllowListAreHeldBack() {
        TextSender s = sender("+2349035911994, 08148849797");
        assertThat(s.send("SMS", "+2348099999999", "hi", null, null).sent()).isFalse();
        assertThat(s.send("SMS", "09035911994", "hi", null, null).sent()).isTrue();
        verify(infobip).sms(eq("2349035911994"), anyString());
    }

    @Test
    void localNigerianNumbersGetTheCountryCode() {
        assertThat(TextSender.number("0803 111 0001")).isEqualTo("2348031110001");
        assertThat(TextSender.number("+234 803 111 0001")).isEqualTo("2348031110001");
        assertThat(TextSender.number("8031110001")).isEqualTo("2348031110001");
        assertThat(TextSender.number("+44 7700 900123")).isEqualTo("447700900123");
    }
}
