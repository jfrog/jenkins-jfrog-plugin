package io.jenkins.plugins.jfrog.maven;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void descriptorDisplayNameMentionsMavenProject() {
        assertEquals("JFrog Artifactory (Maven Project)", new MavenArtifactoryReporter.DescriptorImpl().getDisplayName());
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
}
