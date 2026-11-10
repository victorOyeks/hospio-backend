package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.ServerSocket;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** A wrong port (nothing listening): the send fails as transient, and the password never shows in the error. */
class JavaMailGatewayTest {

    @Test
    void wrongPortFailsTransientlyWithoutThePassword() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        sender.setUsername("user@example.com");
        sender.setPassword("s3cret-Passw0rd");
        Properties p = new Properties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.connectiontimeout", "2000");
        p.put("mail.smtp.timeout", "2000");
        sender.setJavaMailProperties(p);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("mailSender", sender);
        JavaMailGateway gateway = new JavaMailGateway(factory.getBeanProvider(JavaMailSenderImpl.class));

        SmtpGateway.SendFailure f = catchThrowableOfType(() -> gateway.send(new SmtpGateway.Email("no-reply@example.com", "Royal Suites",
                "ada@example.com", null, "Hi", "<p>Hi</p>", "Hi")), SmtpGateway.SendFailure.class);
        assertThat(f).isNotNull();
        assertThat(f.isTransient()).isTrue();
        assertThat(f.getMessage()).doesNotContain("s3cret-Passw0rd");
    }

    @Test
    void noHostIsNotConfigured() {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("mailSender", new JavaMailSenderImpl());
        assertThat(new JavaMailGateway(factory.getBeanProvider(JavaMailSenderImpl.class)).configured()).isFalse();
    }

    @Test
    void scrubRemovesThePassword() {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setPassword("abcd1234");
        assertThat(JavaMailGateway.scrub("535 bad abcd1234", s)).isEqualTo("535 bad ****");
    }
}
