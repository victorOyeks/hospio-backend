package com.hospio.identity.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.enums.Role;
import com.hospio.identity.entity.UserAccount;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The messages identity would send: a temporary password, an invite link, a phone code. Pure text building;
 * {@link com.hospio.common.delivery.DeliveryService} publishes and echoes them. Invite links open the admin
 * portal for platform staff and marketers and the hotel app for everyone else.
 */
@Component
public class DeliveryComposer {

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.of("Africa/Lagos"));

    private final String hotelUrl;
    private final String adminUrl;

    public DeliveryComposer(@Value("${hospio.apps.hotel-url:https://web-staging.hospio360.com}") String hotelUrl,
                            @Value("${hospio.apps.admin-url:https://admin-staging.hospio360.com}") String adminUrl) {
        this.hotelUrl = trimSlash(hotelUrl);
        this.adminUrl = trimSlash(adminUrl);
    }

    /** The app the person signs in to, by the roles they hold. */
    public String appUrl(Collection<Role> roles) {
        boolean admin = roles != null && roles.stream().anyMatch(r -> r.isPlatform() || r == Role.MARKETER);
        return admin ? adminUrl : hotelUrl;
    }

    public String inviteLink(Collection<Role> roles, String token) {
        return appUrl(roles) + "/accept-invite?token=" + token;
    }

    /** Temporary password email, invite email and (with a phone) invite SMS for a created or re-invited login. */
    public List<Delivery> forLogin(UserAccount user, Collection<Role> roles, String temporaryPassword, Instant passwordExpiresAt,
                                   String inviteToken, Instant inviteExpiresAt) {
        List<Delivery> out = new ArrayList<>();
        String app = appUrl(roles);
        if (temporaryPassword != null) {
            out.add(Delivery.email(user.getEmail(), "Your Hospio360 temporary password",
                    "Hello " + user.getFullName() + ",\n\n"
                            + "A Hospio360 login was set up for you.\n\n"
                            + "Email: " + user.getEmail() + "\n"
                            + "Temporary password: " + temporaryPassword + "\n"
                            + (passwordExpiresAt == null ? "" : "It works until " + WHEN.format(passwordExpiresAt) + " and must be changed on first sign-in.\n")
                            + "\nSign in at " + app + "\n",
                    Delivery.Kind.TEMP_PASSWORD, user.getId()));
        }
        if (inviteToken != null) {
            String link = app + "/accept-invite?token=" + inviteToken;
            out.add(Delivery.email(user.getEmail(), "You're invited to Hospio360",
                    "Hello " + user.getFullName() + ",\n\n"
                            + "Open this link to choose your password and sign in:\n" + link + "\n"
                            + (inviteExpiresAt == null ? "" : "\nThe link works until " + WHEN.format(inviteExpiresAt) + ".\n"),
                    Delivery.Kind.INVITE, user.getId()));
            if (StringUtils.hasText(user.getPhone())) {
                // One SMS page: the link (about 95 characters) must stay whole, so the words around it are few.
                out.add(Delivery.sms(user.getPhone(), "Hospio360: set your password here " + link, Delivery.Kind.INVITE, user.getId()));
            }
        }
        return out;
    }

    /** A password reset: the temporary password by email and, with a phone, by SMS; no invite. */
    public List<Delivery> forPasswordReset(UserAccount user, Collection<Role> roles, String temporaryPassword, Instant expiresAt) {
        List<Delivery> out = new ArrayList<>();
        out.add(Delivery.email(user.getEmail(), "Your Hospio360 password was reset",
                "Hello " + user.getFullName() + ",\n\n"
                        + "Your password was reset. Sign in with this temporary password and choose a new one:\n\n"
                        + "Temporary password: " + temporaryPassword + "\n"
                        + (expiresAt == null ? "" : "It works until " + WHEN.format(expiresAt) + ".\n")
                        + "\nSign in at " + appUrl(roles) + "\n",
                Delivery.Kind.PASSWORD_RESET, user.getId()));
        if (StringUtils.hasText(user.getPhone())) {
            out.add(Delivery.sms(user.getPhone(),
                    "Hospio360: password reset. Temporary password: " + temporaryPassword + " - change it when you sign in at " + appUrl(roles),
                    Delivery.Kind.PASSWORD_RESET, user.getId()));
        }
        return out;
    }

    public Delivery forPhoneCode(UserAccount user, String code, Instant expiresAt) {
        return forPhoneCode(user, code, expiresAt, false);
    }

    /**
     * The phone code by SMS, or on WhatsApp as the oyeks_otp code template ("<code> is your verification code") with the
     * same short text as its SMS fallback (docs/sms-whatsapp.md).
     */
    public Delivery forPhoneCode(UserAccount user, String code, Instant expiresAt, boolean whatsApp) {
        String text = "Your Hospio360 code is " + code + ". It works for the next "
                + Math.max(1, java.time.Duration.between(Instant.now(), expiresAt).toMinutes()) + " minutes. Don't share it.";
        return whatsApp
                ? Delivery.whatsApp(user.getPhone(), "Your Hospio360 code", text, Delivery.Kind.PHONE_CODE, user.getId())
                        .withTemplate("oyeks_otp", List.of(code))
                : Delivery.sms(user.getPhone(), text, Delivery.Kind.PHONE_CODE, user.getId());
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
