package com.hospio.messaging.mail;

import com.hospio.common.integrations.settings.IntegrationCatalogue;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP through Spring Mail's configured session (spring.mail.*: Brevo, STARTTLS on 587). The transport is opened here,
 * not through {@code JavaMailSender.send}, so the relay's reply line ("250 ... queued as ...") can be recorded. Errors
 * are reduced to one line; the password is never part of them (and is scrubbed if a provider ever echoed it).
 *
 * <p>When host, port, username or password is saved in the admin portal (Settings · Integrations · SMTP), a sender is built from
 * those laid over spring.mail.* (same session properties) and rebuilt when they change; with none saved, Spring's sender is used as is.
 */
@Slf4j
@Component
public class JavaMailGateway implements SmtpGateway {

    /** The connection settings a portal-built sender was made from, so it is rebuilt only when they change. */
    private record Built(IntegrationSettings.Smtp settings, JavaMailSenderImpl sender) {
    }

    private final ObjectProvider<JavaMailSenderImpl> sender;
    private IntegrationSettingsReader integrations;
    private volatile Built built;

    public JavaMailGateway(ObjectProvider<JavaMailSenderImpl> sender) {
        this.sender = sender;
    }

    @Autowired(required = false)
    public void setIntegrations(IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    /** The sender in force: Spring's (spring.mail.*), or one built from the portal's SMTP settings laid over it. */
    JavaMailSenderImpl current() {
        JavaMailSenderImpl base = sender.getIfAvailable();
        IntegrationSettings.Smtp saved = integrations == null ? null : integrations.saved(IntegrationSettings.Smtp.class).orElse(null);
        if (saved == null || (saved.host() == null && saved.port() == null && saved.username() == null && saved.password() == null)) {
            return base;
        }
        IntegrationSettings.Smtp env = base == null ? new IntegrationSettings.Smtp(null, 587, null, null, null)
                : new IntegrationSettings.Smtp(base.getHost(), base.getPort(), base.getUsername(), base.getPassword(), null);
        IntegrationSettings.Smtp in = IntegrationCatalogue.overlay(new IntegrationSettings.Smtp(saved.host(), saved.port(), saved.username(),
                saved.password(), null), env);
        Built b = built;
        if (b == null || !b.settings().equals(in)) {
            b = new Built(in, build(in, base));
            built = b;
        }
        return b.sender();
    }

    private static JavaMailSenderImpl build(IntegrationSettings.Smtp in, JavaMailSenderImpl base) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(in.host());
        s.setPort(in.port() == null ? 587 : in.port());
        s.setUsername(in.username());
        s.setPassword(in.password());
        s.setProtocol(base == null || base.getProtocol() == null ? "smtp" : base.getProtocol());
        s.setDefaultEncoding("UTF-8");
        Properties props = new Properties();
        if (base != null) {
            props.putAll(base.getJavaMailProperties());
        } else {
            // As spring.mail.properties in application.yml.
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "15000");
            props.put("mail.smtp.writetimeout", "15000");
        }
        s.setJavaMailProperties(props);
        return s;
    }

    @Override
    public boolean configured() {
        JavaMailSenderImpl s = current();
        return s != null && s.getHost() != null && !s.getHost().isBlank();
    }

    /** The admin portal's test: signs in to the relay with the settings in force and leaves; sends nothing. */
    public IntegrationTestResult check() {
        JavaMailSenderImpl s = current();
        if (s == null || s.getHost() == null || s.getHost().isBlank()) {
            return IntegrationTestResult.failed("There is no SMTP host, saved here or on the server");
        }
        Transport transport = null;
        try {
            transport = s.getSession().getTransport(s.getProtocol() == null ? "smtp" : s.getProtocol());
            transport.connect(s.getHost(), s.getPort(), s.getUsername(), s.getPassword());
            return IntegrationTestResult.ok("Signed in to " + s.getHost() + ":" + s.getPort());
        } catch (AuthenticationFailedException e) {
            return IntegrationTestResult.failed(scrub("The relay refused the username or password: " + oneLine(e.getMessage()), s));
        } catch (MessagingException e) {
            return IntegrationTestResult.failed(scrub("Couldn't connect to " + s.getHost() + ":" + s.getPort() + ": " + describe(e), s));
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // only signing in was tested
                }
            }
        }
    }

    @Override
    public Accepted send(Email email) {
        JavaMailSenderImpl s = current();
        if (s == null || s.getHost() == null || s.getHost().isBlank()) {
            throw new SendFailure("No SMTP host is configured", false, null);
        }
        Session session = s.getSession();
        Transport transport = null;
        try {
            MimeMessage message = new MimeMessage(session);
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(email.fromAddress(), email.fromName(), StandardCharsets.UTF_8.name()));
            helper.setTo(new InternetAddress(email.to(), true));
            if (email.replyTo() != null && !email.replyTo().isBlank()) {
                helper.setReplyTo(new InternetAddress(email.replyTo(), true));
            }
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            message.saveChanges();
            transport = session.getTransport(s.getProtocol() == null ? "smtp" : s.getProtocol());
            transport.connect(s.getHost(), s.getPort(), s.getUsername(), s.getPassword());
            transport.sendMessage(message, message.getAllRecipients());
            String reply = transport instanceof SMTPTransport t ? oneLine(t.getLastServerResponse()) : null;
            return new Accepted(message.getMessageID(), reply);
        } catch (AuthenticationFailedException e) {
            throw new SendFailure(scrub("SMTP authentication failed: " + e.getMessage(), s), false, e);
        } catch (SMTPSendFailedException e) {
            throw new SendFailure(scrub(e.getReturnCode() + " " + oneLine(e.getMessage()), s), e.getReturnCode() >= 400 && e.getReturnCode() < 500, e);
        } catch (SMTPAddressFailedException e) {
            throw new SendFailure(scrub(e.getReturnCode() + " " + oneLine(e.getMessage()), s), e.getReturnCode() >= 400 && e.getReturnCode() < 500, e);
        } catch (SendFailedException e) {
            Exception next = e.getNextException();
            if (next instanceof SMTPAddressFailedException a) {
                throw new SendFailure(scrub(a.getReturnCode() + " " + oneLine(a.getMessage()), s), a.getReturnCode() >= 400 && a.getReturnCode() < 500, e);
            }
            throw new SendFailure(scrub("Refused: " + oneLine(e.getMessage()), s), false, e);
        } catch (MessagingException e) {
            throw new SendFailure(scrub(describe(e), s), isNetwork(e), e);
        } catch (UnsupportedEncodingException e) {
            throw new SendFailure("Bad sender name: " + e.getMessage(), false, e);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // the message is already handed over (or not); closing late changes nothing
                }
            }
        }
    }

    private static boolean isNetwork(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException || t instanceof SocketTimeoutException || t instanceof UnknownHostException
                    || t instanceof java.net.NoRouteToHostException || t instanceof java.io.EOFException) {
                return true;
            }
            if (t instanceof MessagingException me && me.getNextException() != null && me.getNextException() != t) {
                if (isNetwork(me.getNextException())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(MessagingException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = oneLine(e.getMessage());
        return root == e ? msg : msg + " (" + root.getClass().getSimpleName() + ": " + oneLine(root.getMessage()) + ")";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static String scrub(String text, JavaMailSenderImpl s) {
        String out = text == null ? "" : text;
        String pw = s == null ? null : s.getPassword();
        if (pw != null && pw.length() >= 4) {
            out = out.replace(pw, "****");
        }
        return out.length() > 480 ? out.substring(0, 480) : out;
    }
}
