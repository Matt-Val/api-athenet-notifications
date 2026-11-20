package com.athenet.notifications.mail;

public interface MailTransport {
    void send(String recipient, String subject, String body);
}
