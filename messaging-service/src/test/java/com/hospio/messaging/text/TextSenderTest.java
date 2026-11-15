package com.hospio.messaging.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private final TermiiClient termii = mock(TermiiClient.class);

    @BeforeEach
    void setUp() {
        when(infobip.configured()).thenReturn(true);
        when(infobip.whatsAppConfigured()).thenReturn(true);
        when(infobip.sms(anyString(), anyString())).thenReturn(new InfobipClient.Sent(true, "s1", null));
        when(infobip.whatsAppTemplate(anyString(), anyString(), anyString(), any(), any(InfobipClient.TemplateInfo.class), any())).thenReturn(new InfobipClient.Sent(true, "w1", null));
    }

    private TextSender sender(String allowOnly) {
        return new TextSender(infobip, termii, true, true, "en", allowOnly);
    }

    @Test
    void anApprovedTemplateGoesOnWhatsApp() {
        when(infobip.templates()).thenReturn(Map.of("hospio_booking_changed", new InfobipClient.TemplateInfo("APPROVED", "UTILITY", "en")));
        TextSender.Outcome o = sender("").send("WHATSAPP", "08031110001", "fallback", "hospio_booking_changed", List.of("Ada", "BK-1", "Royal", "a", "b"), false);
        assertThat(o.sent()).isTrue();
        assertThat(o.channel()).isEqualTo("WHATSAPP");
        verify(infobip).whatsAppTemplate(eq("2348031110001"), eq("hospio_booking_changed"), eq("en"), any(), any(InfobipClient.TemplateInfo.class), any());
        verify(infobip, never()).sms(anyString(), anyString());
    }

    @Test
    void aMessageWhoseTemplateIsInReviewIsNotSentBySms() {
        // SMS is only for sign-in codes: a booking message waits for its WhatsApp template instead.
        when(infobip.templates()).thenReturn(Map.of("hospio_booking_changed", new InfobipClient.TemplateInfo("PENDING", "UTILITY", "en")));
        TextSender.Outcome o = sender("").send("WHATSAPP", "+234 803 111 0001", "text", "hospio_booking_changed", List.of(), false);
        assertThat(o.sent()).isFalse();
        assertThat(o.note()).contains("pending").contains("SMS is only used for sign-in codes");
        verify(infobip, never()).sms(anyString(), anyString());
        verify(termii, never()).sms(anyString(), anyString());
    }

    @Test
    void aCodeFallsBackToAOnePageSmsThroughTermiiWhenItIsOn() {
        when(termii.configured()).thenReturn(true);
        when(termii.sms(anyString(), anyString())).thenReturn(new InfobipClient.Sent(true, "t1", null));
        when(infobip.templates()).thenReturn(Map.of("oyeks_otp", new InfobipClient.TemplateInfo("PENDING", "AUTHENTICATION", "en")));
        String longText = "Your Hospio360 code is 123456 · it works for the next 10 minutes. ".repeat(4);
        TextSender.Outcome o = sender("").send("WHATSAPP", "+234 803 111 0001", longText, "oyeks_otp", List.of("123456"), true);
        assertThat(o.sent()).isTrue();
        assertThat(o.channel()).isEqualTo("SMS");
        assertThat(o.providerRef()).isEqualTo("termii:t1");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(termii).sms(eq("2348031110001"), text.capture());
        assertThat(text.getValue().length()).isLessThanOrEqualTo(160);
        assertThat(text.getValue()).doesNotContain("·");
        verify(infobip, never()).sms(anyString(), anyString());
    }

    @Test
    void withTermiiOffACodeGoesByInfobipSms() {
        TextSender.Outcome o = sender("").send("SMS", "08031110001", "Your code is 123456", null, null, true);
        assertThat(o.providerRef()).isEqualTo("sms:s1");
        verify(infobip).sms(eq("2348031110001"), anyString());
    }

    @Test
    void onlyCodesAreLiveOnSms() {
        assertThat(sender("").live("SMS", "PHONE_CODE")).isTrue();
        assertThat(sender("").live("SMS", "RECEIPT")).isFalse();
    }

    @Test
    void aCodeTemplateCarriesTheCodeOnItsButton() {
        when(infobip.templates()).thenReturn(Map.of("oyeks_otp", new InfobipClient.TemplateInfo("APPROVED", "AUTHENTICATION", "en")));
        sender("").send("WHATSAPP", "2348031110001", "Your code is 123456", "oyeks_otp", List.of("123456"), true);
        verify(infobip).whatsAppTemplate(eq("2348031110001"), eq("oyeks_otp"), eq("en"), eq(List.of("123456")),
                org.mockito.ArgumentMatchers.argThat((InfobipClient.TemplateInfo t) -> "AUTHENTICATION".equals(t.category())), any());
    }

    @Test
    void numbersOffTheAllowListAreHeldBack() {
        TextSender s = sender("+2349035911994, 08148849797");
        assertThat(s.send("SMS", "+2348099999999", "hi", null, null, true).sent()).isFalse();
        assertThat(s.send("SMS", "09035911994", "hi", null, null, true).sent()).isTrue();
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
