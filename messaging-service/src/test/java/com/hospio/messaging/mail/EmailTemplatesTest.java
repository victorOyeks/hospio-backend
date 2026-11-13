package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/email.md: one designed layout, a look per kind, the hotel's brand colour (Hospio360's maroon when none is set). */
class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates();

    @Test
    void layoutHasTheHotelHeaderBadgeHeadingAndFooter() {
        EmailTemplates.Rendered r = templates.render("PASSWORD_RESET", "Royal Suites Ikeja", "Your Hospio360 password was reset",
                "Hello Ada,\n\nTemporary password: Xy7#kQ\n\nSign in at https://web.example/sign-in.");
        assertThat(r.html()).contains(">Royal Suites Ikeja</td>").contains(">RS</div>").contains(">Security</span>")
                .contains("Your password was reset").contains(">Temporary password</div>").contains(">Xy7#kQ</div>")
                .contains("href=\"https://web.example/sign-in\"").contains(">Sign in</a>").contains("Hospio360 · hotel management by Oyeks Global");
        assertThat(r.text()).startsWith("Royal Suites Ikeja").contains("Temporary password: Xy7#kQ").doesNotContain("<");
        assertThat(r.subject()).isEqualTo("Your Hospio360 password was reset");
    }

    @Test
    void labelValueLinesBecomeADetailsCard() {
        String html = templates.render("BOOKING_CONFIRMATION", "Simpas Ikeja", "Your booking HB1 is confirmed",
                "Hello Ada,\n\nYour booking is confirmed.\n\nReference: HB1\nStay: Sat 19 Dec to Sun 20 Dec\nRoom: Regular").html();
        assertThat(html).contains(">Booking confirmed</span>").contains("Your booking is confirmed")
                .contains(">Reference</td>").contains(">HB1</td>").contains(">Room</td>").contains(">Regular</td>");
    }

    @Test
    void eachKindHasItsLook() {
        assertThat(templates.render("INVITE", "H", "s", "Open https://x.example/a").html()).contains("You&#39;re invited").contains("Accept the invite");
        assertThat(templates.render("BOOKING_CANCELLED", "H", "s", "x").html()).contains("#d92d20").contains("Your booking is cancelled");
        assertThat(templates.render("RECEIPT", "H", "s", "Amount: N45,000").html()).contains(">Payment received</span>").contains("#1e7a45");
        assertThat(templates.render("REPORT", "H", "s", "See https://x.example/r").html()).contains("Your report is ready").contains("Open the report");
        assertThat(templates.render("TEMP_PASSWORD", "H", "s", "pw").html()).contains("Your sign-in details");
        assertThat(templates.render("MESSAGE", "H", "About your stay", "Hello").html()).contains(">About your stay</h1>");
        assertThat(templates.render("DECISION", "H", "Your expense was declined", "x").html()).contains("#d92d20");
    }

    @Test
    void theHotelsBrandColourIsUsedAndHospiosWhenNoneIsSet() {
        EmailTemplates.Brand wine = EmailTemplates.Brand.of("#722F37", null);
        String html = templates.render("BOOKING_CONFIRMATION", "St. Paul's Hotel", "s", "Open https://x.example", wine).html();
        assertThat(html).contains("background:#722f37").doesNotContain("#4c091e");
        assertThat(EmailTemplates.Brand.of("not a colour", null)).isEqualTo(EmailTemplates.Brand.HOSPIO);
        assertThat(EmailTemplates.Brand.of("", "")).isEqualTo(EmailTemplates.Brand.HOSPIO);
        assertThat(templates.render("INVITE", "H", "s", "x").html()).contains("background:#4c091e");
        // A light brand colour gets dark text on its buttons and header.
        assertThat(EmailTemplates.Brand.of("#f5d76e", null).onColour()).isEqualTo("#212121");
        assertThat(EmailTemplates.Brand.of("#1d4ed8", "https://cdn.example/logo.png").logoUrl()).isEqualTo("https://cdn.example/logo.png");
    }

    @Test
    void textIsEscaped() {
        String html = templates.render("MESSAGE", "Tom & Jerry's <Inn>", "s", "<script>alert(1)</script> & co").html();
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("Tom &amp; Jerry&#39;s &lt;Inn&gt;");
    }

    @Test
    void everyEmailHasTheSameFooter() {
        assertThat(templates.render("INVITE", "Hospio360", "s", "x").html()).contains("Hospio360 · hotel management by Oyeks Global");
        assertThat(templates.render("RECEIPT", "Royal Suites Ikeja", "s", "x").html()).contains("Hospio360 · hotel management by Oyeks Global")
                .doesNotContain("Sent by");
        assertThat(templates.render("RECEIPT", "Royal Suites Ikeja", "s", "x").text()).endsWith("Hospio360 · hotel management by Oyeks Global\n");
    }
}
