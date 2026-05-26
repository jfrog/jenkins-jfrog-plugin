package io.jenkins.plugins.jfrog.integration;

import io.jenkins.plugins.jfrog.ReleasesInstaller;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Integration tests verifying that parallel JFrog CLI installations do not corrupt
 * the binary or produce race conditions.
 */
@WithJenkins
class ParallelInstallITest extends PipelineTestBase {

    /**
     * Two parallel pipeline stages that share the same JFrog CLI tool must both
     * complete successfully without binary corruption.
     */
    @Test
    void testParallelStagesSameToolVersion() throws Exception {
        setupJenkins();
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        runPipeline("parallel_install");
    }

    /**
     * Two parallel pipeline stages each using a different CLI version must both
     * complete and produce independent, valid binaries.
     */
    @Test
    void testParallelStagesTwoToolVersions() throws Exception {
        setupJenkins();
        // Tool 1: latest CLI version
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        // Tool 2: a known older specific version to verify independent parallel installs
        ReleasesInstaller installer = new ReleasesInstaller();
        installer.setVersion("2.29.2");
        configureJfrogCliTool(JFROG_CLI_TOOL_NAME_2, installer, false);
        runPipeline("parallel_install_two_tools");
    }

    /**
     * Repeated installs of the same version should detect the valid cached binary
     * and skip re-download (verified by both runs succeeding without error).
     */
    @Test
    void testRepeatedInstallSkipsDownload() throws Exception {
        setupJenkins();
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        // First run installs
        runPipeline("basic_version_command");
        // Second run should hit the cache
        runPipeline("basic_version_command");
    }

    /**
     * Install from Artifactory in parallel: both stages must succeed.
     */
    @Test
    void testParallelInstallFromArtifactory() throws Exception {
        setupJenkins();
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_1, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), true);
        runPipeline("parallel_install");
    }
}
