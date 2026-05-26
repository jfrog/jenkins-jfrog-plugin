package io.jenkins.plugins.jfrog.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author yahavi
 **/
@SuppressWarnings("HttpUrlsUsage")
class JFrogPlatformBuilderTest {

    @Test
    void testIsUnsafe() {
        assertFalse(JFrogPlatformBuilder.isUnsafe(false, "https://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isUnsafe(false, "https://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isUnsafe(true, "http://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isUnsafe(true, "https://acme.jfrog.io", "http://acme.jfrog.io"));

        assertTrue(JFrogPlatformBuilder.isUnsafe(false, "http://acme.jfrog.io"));
        assertTrue(JFrogPlatformBuilder.isUnsafe(false, "https://acme.jfrog.io", "http://acme.jfrog.io"));
    }

    @Test
    void testIsInvalidProtocolOrEmptyUrl() {
        assertFalse(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl(""));
        assertFalse(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl("http://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl("https://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl("ssh://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl("ssh://acme.jfrog.io", "http://acme.jfrog.io"));

        assertTrue(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl("www.acme.jfrog.io"));
        assertTrue(JFrogPlatformBuilder.isInvalidProtocolOrEmptyUrl("https://acme.jfrog.io", "www.acme.jfrog.io"));
    }
}
