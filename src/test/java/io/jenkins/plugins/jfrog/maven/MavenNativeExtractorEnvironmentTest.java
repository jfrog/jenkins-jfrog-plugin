package io.jenkins.plugins.jfrog.maven;

import hudson.FilePath;
import hudson.model.Action;
import io.jenkins.plugins.jfrog.actions.BuildInfoBuildBadgeAction;
import org.junit.jupiter.api.Test;

import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void rewritesLegacyWebappBuildInfoUrlToPlatformUi() {
        assertEquals(
                "https://bukgradlefix.jfrogdev.org/ui/builds/mvn-new-1/37",
                MavenNativeExtractorEnvironment.toPlatformUiBuildInfoUrl(
                        "https://bukgradlefix.jfrogdev.org/artifactory/webapp/builds/mvn-new-1/37"));
    }

    @Test
    void leavesPlatformUiBuildInfoUrlUnchanged() {
        String url = "https://bukgradlefix.jfrogdev.org/ui/builds/mvn-new-2/";
        assertEquals(url, MavenNativeExtractorEnvironment.toPlatformUiBuildInfoUrl(url));
    }

    @Test
    void publishedBuildInfoActionUsesSameBadgeAndSidebarAsPipeline() {
        String url = "https://example.jfrog.io/ui/builds/job/15";
        Action action = MavenNativeExtractorEnvironment.publishedBuildInfoAction(url);

        assertTrue(action instanceof BuildInfoBuildBadgeAction);
        assertEquals("Artifactory Build Info", action.getDisplayName());
        assertEquals("/plugin/jfrog/icons/artifactory-icon.png", action.getIconFileName());
        assertEquals(url, action.getUrlName());
    }

    @Test
    void acceptsMavenVersionsCompatibleWithResolution() {
        assertDoesNotThrow(() -> MavenNativeExtractorEnvironment.assertMavenVersionSupportsResolution("3.0.2"));
        assertDoesNotThrow(() -> MavenNativeExtractorEnvironment.assertMavenVersionSupportsResolution("3.8.8"));
        assertDoesNotThrow(() -> MavenNativeExtractorEnvironment.assertMavenVersionSupportsResolution("3.9.11"));
        // 2.43.6+ extractor injects prerequisitesCheckers (build-info#841).
        assertDoesNotThrow(() -> MavenNativeExtractorEnvironment.assertMavenVersionSupportsResolution("3.9.12"));
        assertDoesNotThrow(() -> MavenNativeExtractorEnvironment.assertMavenVersionSupportsResolution("3.9.15"));
    }

    @Test
    void rejectsMavenTooOldForResolution() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MavenNativeExtractorEnvironment.assertMavenVersionSupportsResolution("3.0.1"));
        assertTrue(ex.getMessage().contains("3.0.2"));
    }

    @Test
    void failsClosedWhenMavenVersionCannotBeDetected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MavenNativeExtractorEnvironment.requireMavenVersionForResolution(null));
        assertTrue(ex.getMessage().contains("could not determine"));
    }

    @Test
    void propertiesDirectoryPrefersNodeRootSoScmCheckoutCannotDeleteIt() {
        FilePath nodeRoot = new FilePath(new File("/tmp/jfrog-node-root"));
        FilePath workspace = new FilePath(new File("/tmp/jfrog-workspace"));

        assertEquals(nodeRoot, MavenNativeExtractorEnvironment.propertiesDirectory(workspace, nodeRoot));
        assertEquals(nodeRoot, MavenNativeExtractorEnvironment.propertiesDirectory(null, nodeRoot));
        assertEquals(workspace, MavenNativeExtractorEnvironment.propertiesDirectory(workspace, null));
        assertNull(MavenNativeExtractorEnvironment.propertiesDirectory(null, null));
    }

    @Test
    void publisherNeedsTargetRepoOnlyWhenDeployingArtifacts() {
        assertTrue(MavenNativeExtractorEnvironment.publisherNeedsTargetRepo(true, false));
        assertFalse(MavenNativeExtractorEnvironment.publisherNeedsTargetRepo(false, true));
        assertTrue(MavenNativeExtractorEnvironment.publisherNeedsTargetRepo(true, true));
        assertFalse(MavenNativeExtractorEnvironment.publisherNeedsTargetRepo(false, false));
    }

    @Test
    void skipsDeployableArtifactsWhenNotDeployingOrPublishing() {
        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(new NullLog());

        MavenNativeExtractorEnvironment.applyPublisherArtifactFlags(configuration, false, false);
        assertEquals(Boolean.FALSE, configuration.publisher.shouldAddDeployableArtifacts());
        assertEquals(Boolean.FALSE, configuration.publisher.isPublishArtifacts());

        MavenNativeExtractorEnvironment.applyPublisherArtifactFlags(configuration, false, true);
        assertEquals(Boolean.FALSE, configuration.publisher.shouldAddDeployableArtifacts());
        assertEquals(Boolean.FALSE, configuration.publisher.isPublishArtifacts());
        assertEquals(Boolean.TRUE, configuration.publisher.isPublishBuildInfo());
    }

    @Test
    void copiesJenkinsEnvVarsMatchingIncludePatternsAndSkipsSecrets() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JFROG_IT_MARKER", "it-env-abc");
        env.put("JFROG_IT_PASSWORD", "should-be-excluded");
        env.put("UNRELATED", "no");

        Map<String, String> captured = MavenNativeExtractorEnvironment.capturedJenkinsEnvVars(
                env, "JFROG_IT_MARKER;GIT_*", "*password*;*psw*;*secret*;*key*;*token*;*auth*");

        assertEquals("it-env-abc", captured.get("JFROG_IT_MARKER"));
        assertFalse(captured.containsKey("JFROG_IT_PASSWORD"));
        assertFalse(captured.containsKey("UNRELATED"));
    }

    @Test
    void extractorEnvPatternsKeepPrefixedIncludeKeysAndDropSecrets() {
        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(new NullLog());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JFROG_IT_MARKER", "it-env-abc");
        env.put("JFROG_IT_PASSWORD", "should-be-excluded");

        MavenNativeExtractorEnvironment.applyCapturedEnvVars(
                configuration, env, "JFROG_IT_MARKER;GIT_*",
                "*password*;*psw*;*secret*;*key*;*token*;*auth*");

        assertEquals(Boolean.TRUE, configuration.isIncludeEnvVars());
        assertTrue(configuration.getAllProperties().containsValue("it-env-abc"),
                configuration.getAllProperties().toString());
        assertFalse(configuration.getAllProperties().containsValue("should-be-excluded"),
                configuration.getAllProperties().toString());

        IncludeExcludePatterns extractorPatterns = new IncludeExcludePatterns(
                configuration.getEnvVarsIncludePatterns(),
                configuration.getEnvVarsExcludePatterns());
        assertFalse(PatternMatcher.pathConflicts("buildInfo.env.JFROG_IT_MARKER", extractorPatterns),
                configuration.getEnvVarsIncludePatterns());
        assertFalse(PatternMatcher.pathConflicts("buildInfo.env.GIT_URL", extractorPatterns),
                configuration.getEnvVarsIncludePatterns());
        assertTrue(PatternMatcher.pathConflicts("buildInfo.env.UNRELATED", extractorPatterns),
                configuration.getEnvVarsIncludePatterns());
        assertTrue(PatternMatcher.pathConflicts("buildInfo.env.JFROG_IT_PASSWORD", extractorPatterns),
                configuration.getEnvVarsExcludePatterns());
    }
}
