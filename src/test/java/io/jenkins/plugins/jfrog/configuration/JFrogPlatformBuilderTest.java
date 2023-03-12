package io.jenkins.plugins.jfrog.configuration;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author yahavi
 **/
public class JFrogPlatformBuilderTest {

    @SuppressWarnings("HttpUrlsUsage")
    @Test
    public void testIsUnsafe() {
        assertFalse(JFrogPlatformBuilder.isUnsafe(false, "https://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isUnsafe(false, "https://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isUnsafe(true, "http://acme.jfrog.io"));
        assertFalse(JFrogPlatformBuilder.isUnsafe(true, "https://acme.jfrog.io", "http://acme.jfrog.io"));

        assertTrue(JFrogPlatformBuilder.isUnsafe(false, "http://acme.jfrog.io"));
        assertTrue(JFrogPlatformBuilder.isUnsafe(false, "https://acme.jfrog.io", "http://acme.jfrog.io"));
    }
}
