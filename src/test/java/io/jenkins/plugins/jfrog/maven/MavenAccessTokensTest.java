package io.jenkins.plugins.jfrog.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenAccessTokensTest {

    @Test
    void extractUsernameFromJwtSubject() throws IOException {
        String token = jwtWithSubject("jfrt@abc/users/alice");

        assertEquals("alice", MavenAccessTokens.extractUsername(token));
    }

    @Test
    void extractUsernameFromUrlSafeJwt() throws IOException {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"jfrt@abc/users/bob\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals("bob", MavenAccessTokens.extractUsername(header + "." + payload + ".sig"));
    }

    @Test
    void extractUsernameRejectsOpaqueToken() {
        assertThrows(IOException.class, () -> MavenAccessTokens.extractUsername("not-a-jwt"));
    }

    @Test
    void usernameOrEmptyFallsBackForOpaqueToken() {
        assertEquals("", MavenAccessTokens.usernameOrEmpty("not-a-jwt"));
        assertEquals("alice", MavenAccessTokens.usernameOrEmpty(jwtWithSubject("jfrt@abc/users/alice")));
    }

    private static String jwtWithSubject(String subject) {
        String header = Base64.getEncoder().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getEncoder().encodeToString(
                ("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}
