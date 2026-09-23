package io.jenkins.plugins.jfrog.maven;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenNativeExtractorEnvironmentTest {

    @Test
    void parsesSemicolonSeparatedKeyValuePairs() {
        Map<String, String> result = MavenNativeExtractorEnvironment.parseDeploymentProperties(
                "status=staging;region=us");

        assertEquals(2, result.size());
        assertEquals("staging", result.get("status"));
        assertEquals("us", result.get("region"));
    }

    @Test
    void trimsWhitespaceAroundKeysAndValues() {
        Map<String, String> result = MavenNativeExtractorEnvironment.parseDeploymentProperties(
                " status = staging ; region = us ");

        assertEquals("staging", result.get("status"));
        assertEquals("us", result.get("region"));
    }

    @Test
    void skipsBlankEntriesFromRepeatedOrTrailingSemicolons() {
        Map<String, String> result = MavenNativeExtractorEnvironment.parseDeploymentProperties(
                "status=staging;;region=us;");

        assertEquals(2, result.size());
    }

    @Test
    void skipsEntriesWithoutAnEqualsSign() {
        Map<String, String> result = MavenNativeExtractorEnvironment.parseDeploymentProperties(
                "status=staging;notapair;region=us");

        assertEquals(2, result.size());
        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("region"));
    }

    @Test
    void skipsEntriesWithBlankKey() {
        Map<String, String> result = MavenNativeExtractorEnvironment.parseDeploymentProperties(
                "=novalue;status=staging");

        assertEquals(1, result.size());
        assertEquals("staging", result.get("status"));
    }

    @Test
    void allowsEqualsSignWithinTheValue() {
        Map<String, String> result = MavenNativeExtractorEnvironment.parseDeploymentProperties(
                "query=a=b");

        assertEquals("a=b", result.get("query"));
    }

    @Test
    void emptyInputProducesEmptyMap() {
        assertTrue(MavenNativeExtractorEnvironment.parseDeploymentProperties("").isEmpty());
    }
}
