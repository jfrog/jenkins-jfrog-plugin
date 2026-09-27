package io.jenkins.plugins.jfrog.maven;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableJenkins
class MavenArtifactoryReporterTest {

    @Test
    void deployAndPublishBuildInfoDefaultOffAndCanBeEnabled() {
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();

        assertFalse(reporter.isDeployArtifacts());
        assertFalse(reporter.isPublishBuildInfo());
        reporter.setDeployArtifacts(true);
        reporter.setPublishBuildInfo(true);
        assertTrue(reporter.isDeployArtifacts());
        assertTrue(reporter.isPublishBuildInfo());
    }

    @Test
    void doCheckRequiresConfiguredServerWhenDeployOrPublishBuildInfoIsChecked(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        FormValidation empty = descriptor.doCheckServerId(null, "", true, false);
        assertEquals(FormValidation.Kind.ERROR, empty.kind);
        assertTrue(empty.getMessage().contains("No JFrog Platform instances configured"));

        FormValidation unknown = descriptor.doCheckServerId(null, "prod", false, true);
        assertEquals(FormValidation.Kind.ERROR, unknown.kind);
        assertTrue(unknown.getMessage().contains("Unknown JFrog Platform server"));

        // Optional when neither Deploy Artifacts nor Capture and publish build info is checked -
        // resolution-only use, matching the legacy plugin's posture where the deploy publisher is
        // a separate, optional block from the resolver wrapper.
        FormValidation resolutionOnly = descriptor.doCheckServerId(null, "", false, false);
        assertEquals(FormValidation.Kind.OK, resolutionOnly.kind);
    }

    @Test
    void doCheckValidatesRepository(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckReleaseRepo(null, " ", true).kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckReleaseRepo(null, "../secret", true).kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckReleaseRepo(null, "repo\\win", true).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckReleaseRepo(null, "libs-release-local", true).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckReleaseRepo(null, "${MY_REPO}", true).kind);
        // When Deploy Artifacts is off, the repo is no longer required.
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckReleaseRepo(null, " ", false).kind);
    }

    @Test
    void doCheckOptionalBuildIdentifiers(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckBuildName(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckBuildNumber(null, "1").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckBuildName(null, "bad\nname").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckBuildNumber(null, "x".repeat(201)).kind);
    }

    @Test
    void doFillServerIdItemsIncludesBlankOption(JenkinsRule jenkinsRule) {
        ListBoxModel items = new MavenArtifactoryReporter.DescriptorImpl().doFillServerIdItems(null);

        assertFalse(items.isEmpty());
        assertEquals("", items.get(0).value);
    }

    @Test
    void resolveServerIsIndependentOfDeployServerWithNoFallback(JenkinsRule jenkinsRule) {
        registerServer("deploy-server");
        registerServer("resolve-server");

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId("deploy-server");
        // resolveServerId intentionally left unset: must NOT fall back to deploy-server.
        assertNull(reporter.findResolveServer());

        reporter.setResolveServerId("resolve-server");
        assertEquals("resolve-server", reporter.findResolveServer().getId());
    }

    @Test
    void resolveServerIsNullWhenIdDoesNotMatchAConfiguredServer(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setResolveServerId("does-not-exist");

        assertNull(reporter.findResolveServer());
    }

    @Test
    void doCheckOptionalRepoFieldsAcceptBlankAndValidateWhenSet(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckSnapshotRepo(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolveSnapshotRepo(null, "").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckSnapshotRepo(null, "repo\\win").kind);
    }

    @Test
    void doCheckResolveRepoIsRequired(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        FormValidation blank = descriptor.doCheckResolveRepo(null, "");
        assertEquals(FormValidation.Kind.ERROR, blank.kind);
        assertTrue(blank.getMessage().contains("required"));
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolveRepo(null, "maven-virtual").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckResolveRepo(null, "../secret").kind);
    }

    @Test
    void doCheckResolveServerIdIsRequired(JenkinsRule jenkinsRule) {
        registerServer("known-server");
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        FormValidation blank = descriptor.doCheckResolveServerId(null, "", "");
        assertEquals(FormValidation.Kind.ERROR, blank.kind);
        assertTrue(blank.getMessage().contains("required"));
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckResolveServerId(null, "", "maven-virtual").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolveServerId(null, "known-server", "").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckResolveServerId(null, "unknown-server", "").kind);
    }

    @Test
    void publishesBuildInfoNativelyRequiresServerAndFlag() {
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setPublishBuildInfo(true);
        assertFalse(reporter.publishesBuildInfoNatively());

        reporter.setServerId("acme");
        assertTrue(reporter.publishesBuildInfoNatively());

        reporter.setPublishBuildInfo(false);
        assertFalse(reporter.publishesBuildInfoNatively());
    }

    @Test
    void doCheckDeploymentPropertiesValidatesKeyValueFormat(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckDeploymentProperties(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckDeploymentProperties(null, "status=staging;region=us").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckDeploymentProperties(null, "notapair").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckDeploymentProperties(null, "=novalue").kind);
    }

    @Test
    void doCheckEnvVarAndArtifactPatternFieldsAcceptBlankAndRejectLineBreaks(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEnvVarsIncludePatterns(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEnvVarsExcludePatterns(null, "BUILD_*;JOB_*").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckEnvVarsIncludePatterns(null, "bad\nvalue").kind);

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckArtifactIncludePatterns(null, "*.jar").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckArtifactExcludePatterns(null, "").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckArtifactExcludePatterns(null, "bad\nvalue").kind);
    }

    @Test
    void doCheckProjectAcceptsBlankAndRejectsLineBreaks(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckProject(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckProject(null, "myproj").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckProject(null, "bad\nvalue").kind);
    }

    private static void registerServer(String id) {
        JFrogPlatformInstance instance = new JFrogPlatformInstance(
                id,
                "https://example.jfrog.io",
                new CredentialsConfig(Secret.fromString(""), Secret.fromString(""), Secret.fromString(""), "cred-id"),
                "",
                "",
                "");
        JFrogPlatformBuilder.DescriptorImpl descriptor =
                Jenkins.get().getDescriptorByType(JFrogPlatformBuilder.DescriptorImpl.class);
        List<JFrogPlatformInstance> existing = descriptor.getJfrogInstances();
        java.util.ArrayList<JFrogPlatformInstance> updated =
                existing == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(existing);
        updated.add(instance);
        descriptor.setJfrogInstances(updated);
    }
}
