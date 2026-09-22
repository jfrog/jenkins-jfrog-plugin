package io.jenkins.plugins.jfrog.maven;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Derives an Artifactory username from a JFrog access token, matching the official
 * Artifactory Jenkins plugin ({@code convertAccessTokenToUsernamePassword}).
 */
final class MavenAccessTokens {

    private MavenAccessTokens() {
    }

    static String usernameOrEmpty(String accessToken) {
        try {
            return extractUsername(accessToken);
        } catch (IOException ignored) {
            return StringUtils.EMPTY;
        }
    }

    @SuppressFBWarnings("NP_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD")
    static String extractUsername(String accessToken) throws IOException {
        if (StringUtils.isBlank(accessToken) || !accessToken.contains(".")) {
            throw new IOException("Access token is not a JWT; cannot derive a username");
        }
        String[] parts = StringUtils.split(accessToken, '.');
        if (parts.length < 2) {
            throw new IOException("Access token is not a JWT; cannot derive a username");
        }
        byte[] decodedPayload = decodeJwtPayload(parts[1]);
        String jsonStr = new String(decodedPayload, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TokenPayload payloadObject = mapper.readValue(jsonStr, TokenPayload.class);
        if (payloadObject == null || StringUtils.isBlank(payloadObject.sub)) {
            throw new IOException("Access token JWT has no subject");
        }
        int usernameStartIndex = payloadObject.sub.lastIndexOf("/") + 1;
        return payloadObject.sub.substring(usernameStartIndex);
    }

    private static byte[] decodeJwtPayload(String payload) {
        try {
            return Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException ignored) {
            return Base64.getDecoder().decode(payload);
        }
    }

    private static class TokenPayload {
        public String sub;
    }
}
