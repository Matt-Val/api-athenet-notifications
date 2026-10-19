package com.athenet.notifications.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.athenet.notifications.config.SecurityConfig;
import com.athenet.notifications.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NewsletterController.class) @Import(SecurityConfig.class)
class NewsletterControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SubscriptionService subscriptions;
    private static final String TOKEN = "a".repeat(43);

    @Test void legacyRegistrationRouteCannotBypassCaptcha() throws Exception {
        mvc.perform(post("/api/public/newsletter/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(subscriptions);
    }

    @Test void getLinksNeverConfirmOrUnsubscribeAutomatically() throws Exception {
        mvc.perform(get("/api/public/newsletter/confirm").param("token", TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/public/newsletter/unsubscribe").param("token", TOKEN)).andExpect(status().isOk());
        verifyNoInteractions(subscriptions);
    }

    @Test void htmlPagesPreserveFormOriginWithoutExposingTokenInReferrer() throws Exception {
        for (String action : new String[]{"confirm", "unsubscribe"}) {
            mvc.perform(get("http://localhost:8084/api/public/newsletter/" + action).param("token", TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Referrer-Policy", "strict-origin"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(subscriptions);
    }

    @Test void jsonTokenConfirmationCallsService() throws Exception {
        mvc.perform(post("/api/public/newsletter/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk());
        verify(subscriptions).confirm(TOKEN);
    }

    @Test void formUnsubscribeWorksWithoutLogin() throws Exception {
        mvc.perform(post("http://localhost:8084/api/public/newsletter/unsubscribe")
                .header("Origin", "http://localhost:8084").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", TOKEN)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        verify(subscriptions).unsubscribe(TOKEN);
    }

    @Test void opaqueAndUntrustedOriginsCannotUnsubscribe() throws Exception {
        for (String origin : new String[]{"null", "https://untrusted.example"}) {
            mvc.perform(post("http://localhost:8084/api/public/newsletter/unsubscribe")
                    .header("Origin", origin).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("token", TOKEN))
                    .andExpect(status().isForbidden()).andExpect(content().string("Invalid CORS request"));
        }
        verifyNoInteractions(subscriptions);
    }

    @Test void invalidTokenIsRejected() throws Exception {
        mvc.perform(post("/api/public/newsletter/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(subscriptions);
    }

    @Test void unexposedRoutesAreDenied() throws Exception {
        mvc.perform(get("/api/admin/subscribers")).andExpect(status().isForbidden());
        verifyNoInteractions(subscriptions);
    }

    @Test void corsAcceptsConfiguredPublicSiteAndRejectsUnknownOrigin() throws Exception {
        mvc.perform(options("/api/public/newsletter/subscriptions").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isOk());
        mvc.perform(options("/api/public/newsletter/subscriptions").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}
