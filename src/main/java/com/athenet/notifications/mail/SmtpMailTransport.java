package com.athenet.notifications.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import com.athenet.notifications.config.NewsletterProperties;

@Component @RequiredArgsConstructor
public class SmtpMailTransport implements MailTransport {
    private final JavaMailSender sender;
    private final NewsletterProperties properties;

    @Override public void send(String recipient, String subject, String body) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
