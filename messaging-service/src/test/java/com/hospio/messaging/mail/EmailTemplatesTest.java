package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates();

    @Test
    void sharedLayoutHasTheHotelHeaderTheBodyAndAFooter() {
        EmailTemplates.Rendered r = templates.render("PASSWORD_RESET", "Royal Suites Ikeja", "Your Hospio360 password was reset",
                "Hello Ada,\n\nTemporary password: Xy7#kQ\n\nSign in at https://web.example/sign-in.");
        assertThat(r.html()).contains(">Royal Suites Ikeja</td>").contains("Your password was reset").contains("Temporary password: Xy7#kQ")
                .contains("href=\"https://web.example/sign-in\"").contains(">Sign in</a>").contains("Sent by Royal Suites Ikeja with Hospio360")
                .contains("Reply to this email to reach Royal Suites Ikeja");
        assertThat(r.text()).startsWith("Royal Suites Ikeja").contains("Temporary password: Xy7#kQ").doesNotContain("<");
        assertThat(r.subject()).isEqualTo("Your Hospio360 password was reset");
    }

    @Test
    void eachKindHasItsHeading() {
        assertThat(templates.render("INVITE", "H", "s", "Open https://x.example/a").html()).contains("You&#39;re invited").contains("Accept the invite");
        assertThat(templates.render("BOOKING_CONFIRMATION", "H", "s", "Ref ABC").html()).contains("Your booking is confirmed");
        assertThat(templates.render("PURCHASE_ORDER", "H", "s", "Lines").html()).contains("Purchase order");
        assertThat(templates.render("REPORT", "H", "s", "See https://x.example/r").html()).contains("Your report is ready").contains("Open the report");
        assertThat(templates.render("TEMP_PASSWORD", "H", "s", "pw").html()).contains("Your temporary password");
        // a guest message reads as a letter: no heading
        assertThat(templates.render("MESSAGE", "H", "About your stay", "Hello").html()).doesNotContain("<h1");
    }

    @Test
    void textIsEscaped() {
        String html = templates.render("MESSAGE", "Tom & Jerry's <Inn>", "s", "<script>alert(1)</script> & co").html();
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("Tom &amp; Jerry&#39;s &lt;Inn&gt;");
    }

    @Test
    void platformMailHasNoReplyLine() {
        assertThat(templates.render("INVITE", "Hospio360", "s", "x").html()).doesNotContain("Reply to this email");
    }
}
