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
    void deployArtifactsDefaultsTrueAndCanBeDisabled() {
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();

        assertTrue(reporter.isDeployArtifacts());
        reporter.setDeployArtifacts(false);
        assertFalse(reporter.isDeployArtifacts());
    }

    @Test
    void doCheckRequiresConfiguredServer(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        FormValidation empty = descriptor.doCheckServerId(null, "");
        assertEquals(FormValidation.Kind.ERROR, empty.kind);
        assertTrue(empty.getMessage().contains("No JFrog Platform instances configured"));

        FormValidation unknown = descriptor.doCheckServerId(null, "prod");
        assertEquals(FormValidation.Kind.ERROR, unknown.kind);
        assertTrue(unknown.getMessage().contains("Unknown JFrog Platform server"));
    }

    @Test
    void doCheckValidatesRepository(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckArtifactoryRepo(null, " ").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckArtifactoryRepo(null, "../secret").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckArtifactoryRepo(null, "repo\\win").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckArtifactoryRepo(null, "libs-release-local").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckArtifactoryRepo(null, "${MY_REPO}").kind);
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
    void resolverServerFallsBackToDeployServerWhenNotOverridden(JenkinsRule jenkinsRule) {
        registerServer("deploy-server");

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId("deploy-server");

        assertNotNull(reporter.resolveResolverServer());
        assertEquals("deploy-server", reporter.resolveResolverServer().getId());
    }

    @Test
    void resolverServerUsesOverrideWhenSet(JenkinsRule jenkinsRule) {
        registerServer("deploy-server");
        registerServer("resolver-server");

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId("deploy-server");
        reporter.setResolverServerId("resolver-server");

        assertEquals("resolver-server", reporter.resolveResolverServer().getId());
    }

    @Test
    void resolverServerIsNullWhenNeitherIdMatchesAConfiguredServer(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId("does-not-exist");

        assertNull(reporter.resolveResolverServer());
    }

    @Test
    void doCheckOptionalRepoFieldsAcceptBlankAndValidateWhenSet(JenkinsRule jenkinsRule) {
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolveRepo(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckSnapshotRepo(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolveSnapshotRepo(null, "").kind);

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolveRepo(null, "maven-virtual").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckResolveRepo(null, "../secret").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckSnapshotRepo(null, "repo\\win").kind);
    }

    @Test
    void doCheckResolverServerIdAcceptsBlankAndValidatesKnownServer(JenkinsRule jenkinsRule) {
        registerServer("known-server");
        MavenArtifactoryReporter.DescriptorImpl descriptor = new MavenArtifactoryReporter.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolverServerId(null, "").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckResolverServerId(null, "known-server").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckResolverServerId(null, "unknown-server").kind);
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
