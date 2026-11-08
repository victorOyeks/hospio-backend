package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.enums.Role;
import com.hospio.identity.entity.UserAccount;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Invite links point at the right app; the temporary password, link and code appear in the text; no phone, no SMS. */
class DeliveryComposerTest {

    private final DeliveryComposer composer = new DeliveryComposer("https://web-staging.hospio360.com/", "https://admin-staging.hospio360.com");

    private static UserAccount user(String phone) {
        UserAccount u = UserAccount.builder().businessId("b1").fullName("Tunde Bakare").email("tunde@x.ng").phone(phone).build();
        u.setId("u1");
        return u;
    }

    @Test
    void hotelRolesGetTheHotelAppAndPlatformRolesTheAdminPortal() {
        assertThat(composer.inviteLink(Set.of(Role.MANAGER), "tok")).isEqualTo("https://web-staging.hospio360.com/accept-invite?token=tok");
        assertThat(composer.inviteLink(Set.of(Role.OWNER), "tok")).startsWith("https://web-staging.hospio360.com/");
        assertThat(composer.inviteLink(Set.of(Role.SUPPORT), "tok")).isEqualTo("https://admin-staging.hospio360.com/accept-invite?token=tok");
        assertThat(composer.inviteLink(Set.of(Role.MARKETER), "tok")).startsWith("https://admin-staging.hospio360.com/");
    }

    @Test
    void loginDeliveriesCarryPasswordInviteAndSmsWhenThereIsAPhone() {
        Instant pw = Instant.now().plusSeconds(72 * 3600);
        Instant inv = Instant.now().plusSeconds(7 * 86400);
        List<Delivery> out = composer.forLogin(user("+2348010000001"), Set.of(Role.MANAGER), "Abc12345", pw, "tok123", inv);

        assertThat(out).extracting(Delivery::kind).containsExactly(Delivery.Kind.TEMP_PASSWORD, Delivery.Kind.INVITE, Delivery.Kind.INVITE);
        assertThat(out).extracting(Delivery::channel).containsExactly(Delivery.Channel.EMAIL, Delivery.Channel.EMAIL, Delivery.Channel.SMS);
        assertThat(out.get(0).to()).isEqualTo("tunde@x.ng");
        assertThat(out.get(0).text()).contains("Temporary password: Abc12345").contains("https://web-staging.hospio360.com");
        assertThat(out.get(1).text()).contains("/accept-invite?token=tok123");
        assertThat(out.get(2).to()).isEqualTo("+2348010000001");
        assertThat(out.get(2).text()).contains("/accept-invite?token=tok123");
        assertThat(out).allSatisfy(d -> assertThat(d.ref()).isEqualTo("u1"));
    }

    @Test
    void noPhoneMeansNoSmsAndAResetHasNoInvite() {
        List<Delivery> login = composer.forLogin(user(null), Set.of(Role.FRONT_OFFICE), "pw", null, "tok", null);
        assertThat(login).extracting(Delivery::channel).containsExactly(Delivery.Channel.EMAIL, Delivery.Channel.EMAIL);

        List<Delivery> reset = composer.forPasswordReset(user(null), Set.of(Role.FRONT_OFFICE), "Newpass1", Instant.now().plusSeconds(60));
        assertThat(reset).hasSize(1);
        assertThat(reset.get(0).kind()).isEqualTo(Delivery.Kind.PASSWORD_RESET);
        assertThat(reset.get(0).text()).contains("Newpass1");

        Delivery code = composer.forPhoneCode(user("+2348010000001"), "123456", Instant.now().plusSeconds(600));
        assertThat(code.channel()).isEqualTo(Delivery.Channel.SMS);
        assertThat(code.kind()).isEqualTo(Delivery.Kind.PHONE_CODE);
        assertThat(code.text()).contains("123456");
    }
}
