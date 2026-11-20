package com.athenet.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.config.SubscriptionRateLimitProperties;
import com.athenet.notifications.config.TurnstileProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({NewsletterProperties.class, TurnstileProperties.class, SubscriptionRateLimitProperties.class})
public class NotificationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationsApplication.class, args);
    }
}
