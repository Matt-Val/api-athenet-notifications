package com.athenet.notifications.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.athenet.notifications.config.NewsletterProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailTransportTest {
    @Test void sendsToOneRecipientWithoutExposingTheSubscriberList() {
        var sender = mock(JavaMailSender.class);
        var properties = new NewsletterProperties();
        new SmtpMailTransport(sender, properties).send("test@example.com", "Resultado", "A 2–1 B");
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class); verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo(properties.getFrom());
        assertThat(message.getValue().getTo()).containsExactly("test@example.com");
        assertThat(message.getValue().getCc()).isNull();
        assertThat(message.getValue().getBcc()).isNull();
        assertThat(message.getValue().getText()).isEqualTo("A 2–1 B");
    }
}
