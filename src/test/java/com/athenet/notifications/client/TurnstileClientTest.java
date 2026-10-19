package com.athenet.notifications.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.SocketTimeoutException;
import java.util.Set;
import com.athenet.notifications.config.TurnstileProperties;
import com.athenet.notifications.exception.RegistrationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TurnstileClientTest {
    private final TurnstileProperties properties = new TurnstileProperties();
    private MockRestServiceServer server;
    private TurnstileClient client;

    @BeforeEach void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties.setSecretKey("test-secret");
        properties.setAllowedHostnames(Set.of("localhost", "fabetabilo.github.io"));
        client = new TurnstileClient(builder.build(), properties, new ObjectMapper());
    }

    @Test void postsTokenAndPeerIpThenChecksCloudflareHostname() {
        server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("secret=test-secret&response=a%2Bb&remoteip=192.0.2.1"))
                .andRespond(withSuccess("{\"success\":true,\"hostname\":\"fabetabilo.github.io\",\"action\":\"anything\"}", MediaType.APPLICATION_JSON));
        client.verify("a+b", "192.0.2.1");
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"invalid-input-response", "timeout-or-duplicate", "missing-input-response"})
    void invalidExpiredOrReplayedTokenIsForbidden(String code) {
        response("{\"success\":false,\"error-codes\":[\"" + code + "\"]}");
        assertStatus(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest @ValueSource(strings = {"untrusted.example", "fabetabilo.github.io.attacker.example", ""})
    void unexpectedHostnameIsForbidden(String hostname) {
        response("{\"success\":true,\"hostname\":\"" + hostname + "\"}");
        assertStatus(HttpStatus.FORBIDDEN);
    }

    @Test void successWithoutHostnameIsForbidden() {
        response("{\"success\":true}");
        assertStatus(HttpStatus.FORBIDDEN);
    }

    @Test void hostnameDiagnosticNeverExposesSecretOrToken(CapturedOutput output) {
        properties.setSecretKey("private-secret-value");
        response("{\"success\":true,\"hostname\":\"unexpected.example\"}");
        assertThatThrownBy(() -> client.verify("private-token-value", "192.0.2.1"))
                .isInstanceOf(RegistrationException.class);
        assertThat(output.getOut()).contains("hostname rechazado: unexpected.example")
                .doesNotContain("private-secret-value", "private-token-value");
        server.verify();
    }

    @Test void hostnameDiagnosticSanitizesUntrustedCharacters(CapturedOutput output) {
        response("{\"success\":true,\"hostname\":\"bad\\nhost\"}");
        assertStatus(HttpStatus.FORBIDDEN);
        assertThat(output.getOut()).contains("<ausente o inválido>").doesNotContain("bad\nhost");
    }

    @Test void tokenDiagnosticDoesNotExposeRawProviderReply(CapturedOutput output) {
        response("{\"success\":false,\"error-codes\":[\"private-provider-details\"]}");
        assertStatus(HttpStatus.FORBIDDEN);
        assertThat(output.getOut()).contains("Siteverify rechazó el token")
                .doesNotContain("test-secret", "test-token", "private-provider-details");
    }

    @Test void missingBlankOrOversizedTokenNeverCallsCloudflare() {
        for (String token : new String[]{null, "", " ", "a".repeat(2049)}) {
            assertThatThrownBy(() -> client.verify(token, "192.0.2.1"))
                    .isInstanceOfSatisfying(RegistrationException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        server.verify();
    }

    @Test void missingSecretFailsClosedWithoutHttpCall() {
        properties.setSecretKey("");
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest @ValueSource(strings = {"internal-error", "invalid-input-secret", "missing-input-secret", "bad-request"})
    void providerOrConfigurationFailureIsUnavailable(String code) {
        response("{\"success\":false,\"error-codes\":[\"" + code + "\"]}");
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest @ValueSource(strings = {"not-json", "null", "{}", "{\"success\":\"true\"}", ""})
    void malformedOrEmptyReplyFailsClosed(String body) {
        response(body);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void httpFailureIsUnavailable() {
        server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify")).andRespond(withServerError());
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void timeoutIsUnavailable() {
        server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void response(String body) {
        server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void assertStatus(HttpStatus status) {
        assertThatThrownBy(() -> client.verify("test-token", "192.0.2.1"))
                .isInstanceOfSatisfying(RegistrationException.class, ex -> assertThat(ex.getStatus()).isEqualTo(status));
        server.verify();
    }
}
