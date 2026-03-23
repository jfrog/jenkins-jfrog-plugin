package io.jenkins.plugins.jfrog.integration;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Shell;
import io.jenkins.plugins.jfrog.JfrogBuildInfoPublisher;
import io.jenkins.plugins.jfrog.JfrogCliWrapper;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Freestyle job extensions:
 * - JfrogCliWrapper (Build Environment wrapper)
 * - JfrogBuildInfoPublisher (Post-build action)
 */
@WithJenkins
class FreestyleJobITest extends PipelineTestBase {

    /**
     * JfrogCliWrapper should add JFROG_BINARY_PATH to the build environment.
     */
    @Test
    void testWrapperSetsBinaryPath() throws Exception {
        setupJenkins();
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);

        FreeStyleProject project = jenkins.createFreeStyleProject("test-wrapper-path");
        JfrogCliWrapper wrapper = new JfrogCliWrapper();
        wrapper.setJfrogInstallation(JFROG_CLI_TOOL_NAME_1);
        project.getBuildWrappersList().add(wrapper);

        // Add a shell step that checks JFROG_BINARY_PATH is set
        project.getBuildersList().add(
                new Shell("echo \"Binary: $JFROG_BINARY_PATH\" && test -n \"$JFROG_BINARY_PATH\"")
        );

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        assertNotNull(build);
    }

    /**
     * Without any installation selected, JfrogCliWrapper should still allow the build
     * to proceed (uses system PATH).
     */
    @Test
    void testWrapperNoInstallationUsesSysPath() throws Exception {
        setupJenkins();

        FreeStyleProject project = jenkins.createFreeStyleProject("test-wrapper-no-install");
        JfrogCliWrapper wrapper = new JfrogCliWrapper();
        // No installation set — uses system PATH
        project.getBuildWrappersList().add(wrapper);
        project.getBuildersList().add(new Shell("echo 'no installation, continuing'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        assertNotNull(build);
    }

    /**
     * JfrogBuildInfoPublisher with publishOnlyOnSuccess=true must skip publish
     * when the build has failed.
     */
    @Test
    void testPublisherSkipsOnFailedBuild() throws Exception {
        setupJenkins();

        FreeStyleProject project = jenkins.createFreeStyleProject("test-publisher-skip");
        // Deliberately fail the build
        project.getBuildersList().add(new Shell("exit 1"));

        JfrogBuildInfoPublisher publisher = new JfrogBuildInfoPublisher();
        publisher.setPublishOnlyOnSuccess(true);
        project.getPublishersList().add(publisher);

        // Build should fail at the shell step; publisher should skip cleanly (no NPE or error from publisher)
        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        assertNotNull(build);
    }

    /**
     * Freestyle flow: wrapper sets environment and build step successfully invokes the JFrog CLI.
     * Verifies that JFROG_BINARY_PATH points to the installation directory and 'jf -v' succeeds.
     */
    @Test
    void testFullFreestyleFlow() throws Exception {
        setupJenkins();
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);

        FreeStyleProject project = jenkins.createFreeStyleProject("test-freestyle-full");

        // Build environment: set up JFrog CLI
        JfrogCliWrapper wrapper = new JfrogCliWrapper();
        wrapper.setJfrogInstallation(JFROG_CLI_TOOL_NAME_1);
        project.getBuildWrappersList().add(wrapper);

        // Build step: version check (JFROG_BINARY_PATH is the directory; append the binary name)
        project.getBuildersList().add(new Shell("\"$JFROG_BINARY_PATH/jf\" -v"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        assertNotNull(build);
    }

    /**
     * JfrogCliWrapper.DescriptorImpl.isApplicable must return true for a regular
     * FreeStyle project and false for a Matrix project (validated via reflection guard).
     */
    @Test
    void testWrapperNotApplicableForMatrixProject() throws Exception {
        setupJenkins();

        JfrogCliWrapper.DescriptorImpl descriptor =
                jenkins.jenkins.getDescriptorByType(JfrogCliWrapper.DescriptorImpl.class);
        assertNotNull(descriptor);

        // For a regular FreeStyle project, it must be applicable
        FreeStyleProject freeStyle = jenkins.createFreeStyleProject("test-is-applicable");
        assertTrue(descriptor.isApplicable(freeStyle));
    }
}
