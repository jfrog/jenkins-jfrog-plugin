package io.jenkins.plugins.jfrog.integration;

import io.jenkins.plugins.jfrog.ReleasesInstaller;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Integration tests verifying that parallel JFrog CLI installations do not corrupt
 * the binary or produce race conditions.
 */
class ParallelInstallITest extends PipelineTestBase {

    /**
     * Two parallel pipeline stages that share the same JFrog CLI tool must both
     * complete successfully without binary corruption.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testParallelStagesSameToolVersion(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        runPipeline(jenkins, "parallel_install");
    }

    /**
     * Two parallel pipeline stages each using a different CLI version must both
     * complete and produce independent, valid binaries.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testParallelStagesTwoToolVersions(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        // Tool 1: latest CLI version
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        // Tool 2: a known older specific version to verify independent parallel installs
        ReleasesInstaller installer = new ReleasesInstaller();
        installer.setVersion("2.29.2");
        configureJfrogCliTool(JFROG_CLI_TOOL_NAME_2, installer, false);
        runPipeline(jenkins, "parallel_install_two_tools");
    }

    /**
     * Repeated installs of the same version should detect the valid cached binary
     * and skip re-download (verified by both runs succeeding without error).
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testRepeatedInstallSkipsDownload(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        // First run installs
        runPipeline(jenkins, "basic_version_command");
        // Second run should hit the cache
        runPipeline(jenkins, "basic_version_command");
    }

    /**
     * Install from Artifactory in parallel: both stages must succeed.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testParallelInstallFromArtifactory(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_1, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), true);
        runPipeline(jenkins, "parallel_install");
    }
}
