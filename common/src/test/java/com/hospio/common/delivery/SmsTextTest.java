package com.hospio.common.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Every SMS is one page: GSM characters only, 160 at most, links whole. */
class SmsTextTest {

    @Test
    void shortTextIsKeptButBroughtIntoGsm() {
        assertThat(SmsText.fit("Hospio360 · we received ₦45,000 – thank you “Ada”")).isEqualTo("Hospio360 - we received N45,000 - thank you \"Ada\"");
        assertThat(SmsText.fit("Ọlá, your code is 123456")).isEqualTo("Ola, your code is 123456");
    }

    @Test
    void longTextIsCutAtAWordToOnePage() {
        String long_ = "Purchase order PO-0042 from Royal Suites Ikeja: 12 x Peak milk tins, 30 x Indomie cartons, 4 x Golden Penny "
                + "semovita bags, 18 x bottled water packs, 6 x detergent. Deliver by Friday.";
        String sms = SmsText.fit(long_);
        assertThat(SmsText.length(sms)).isLessThanOrEqualTo(160);
        assertThat(sms).endsWith("...").startsWith("Purchase order PO-0042");
    }

    @Test
    void aLinkIsNeverCut() {
        String link = "https://web-staging.hospio360.com/accept-invite?token=Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo";
        String sms = SmsText.fit("Hospio360: Adaeze Okoro-Williamson, you have been invited to Royal Suites Ikeja as Front office. Set your password here: "
                + link + " (until 16 Nov 2026, 18:00)");
        assertThat(SmsText.length(sms)).isLessThanOrEqualTo(160);
        assertThat(sms).endsWith(link).startsWith("Hospio360:");
    }

    @Test
    void extendedCharactersCountTwice() {
        assertThat(SmsText.length("[]")).isEqualTo(4);
        String braces = "{".repeat(100);
        assertThat(SmsText.length(SmsText.fit(braces))).isLessThanOrEqualTo(160);
    }
}
