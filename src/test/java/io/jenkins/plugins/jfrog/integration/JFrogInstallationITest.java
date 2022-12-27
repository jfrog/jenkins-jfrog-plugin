package io.jenkins.plugins.jfrog.integration;

import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.nio.file.Path;

import static io.jenkins.plugins.jfrog.JfrogInstallation.JfrogDependenciesDirName;
import static io.jenkins.plugins.jfrog.Utils.BINARY_NAME;
import static org.junit.jupiter.api.Assertions.*;

class JFrogInstallationITest extends PipelineTestBase {
    // JFrog CLI version which is accessible for all operating systems.
    private static final String jfrogCliTestVersion = "2.29.2";

    /**
     * Download Jfrog CLI from 'releases.jfrog.io' and adds it as a global tool.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testJFrogCliInstallationFromReleases(JenkinsRule jenkins) throws Exception {
        setupPipelineTest(jenkins);
        // Download specific CLI version.
        configureJfrogCliFromReleases(jfrogCliTestVersion, true);
        WorkflowRun job = runPipeline(jenkins, "basic_version_command");
        // Verify specific version was installed.
        assertTrue(job.getLog().contains("jf version " + jfrogCliTestVersion));
        // Download the latest CLI version.
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        job = runPipeline(jenkins, "basic_version_command");
        // Verify newer version was installed.
        assertFalse(job.getLog().contains("jf version " + jfrogCliTestVersion));
    }

    /**
     * Download JFrog CLI from client Artifactory and adds it as a global tool.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testJFrogCliInstallationFromArtifactory(JenkinsRule jenkins) throws Exception {
        setupPipelineTest(jenkins);
        // Download the latest CLI version.
        // Using remote repository to 'releases.jfrog.io' in the client's Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), true);
        WorkflowRun job = runPipeline(jenkins, "basic_version_command");
        assertTrue(job.getLog().contains("jf version "));
    }

    // Gets JfrogInstallation directory in Jenkins work root.
    private Path getJfrogInstallationDir(JenkinsRule jenkins) {
        return jenkins.jenkins.getRootDir().toPath().resolve("tools").resolve("io.jenkins.plugins.jfrog.JfrogInstallation");
    }

    /**
     * Check that only one copy of JFrog CLI is being downloaded to the expected location when running multiple jobs.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testDownloadingJFrogCliOnce(JenkinsRule jenkins) throws Exception {
        initPipelineTest(jenkins);
        // After running job for the first time, CLI's binary should be downloaded.
        runPipeline(jenkins, "basic_version_command");
        long lastModified = getCliLastModified(jenkins);
        // Rerunning the job and verifying that the binary was not downloaded again by comparing the modification time.
        runPipeline(jenkins, "basic_version_command");
        assertEquals(lastModified, getCliLastModified(jenkins));
    }

    /**
     * Finds the binary file in the CLI tool directory and returns its modification time.
     *
     * @param jenkins Jenkins instance injected automatically.
     * @return binary's modification time.
     */
    private long getCliLastModified(JenkinsRule jenkins) {
        Path binaryPath = getJfrogInstallationDir(jenkins);
        String name = BINARY_NAME;
        if (!jenkins.createLocalLauncher().isUnix()) {
            name = name + ".exe";
        }
        binaryPath = binaryPath.resolve(JFROG_CLI_TOOL_NAME).resolve(name);
        assertTrue(binaryPath.toFile().exists());
        return binaryPath.toFile().lastModified();
    }

    /**
     * Check that only one copy of maven extractor is being downloaded to the expected 'dependencies' directory by running a script with a maven command.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    //
    @Test
    public void testDownloadingMavenExtractor(JenkinsRule jenkins) throws Exception {
        initPipelineTest(jenkins);
        // After running job for the first time, Mvn extractor should be downloaded.
        runPipeline(jenkins, "mvn_command");
        Path mvnDependenciesDirPath = getJfrogInstallationDir(jenkins).resolve(JfrogDependenciesDirName).resolve("maven");
        long lastModified = getExtractorLastModified(mvnDependenciesDirPath);
        // Rerunning the job and verifying that the extractor was not downloaded again by comparing the modification time.
        runPipeline(jenkins, "mvn_command");
        assertEquals(lastModified, getExtractorLastModified(mvnDependenciesDirPath));
    }

    /**
     * Finds the extractor' jar in the mvn extractor directory and returns its modification time.
     *
     * @param mvnDependenciesDirPath maven's extractors directory inside the dependencies' directory.
     * @return extractor' jar modification time.
     */
    private long getExtractorLastModified(Path mvnDependenciesDirPath) {
        File[] fileList = mvnDependenciesDirPath.toFile().listFiles();
        assertEquals(1, fileList.length, "The Maven dependencies directory is expected to contain only one extractor version, but it actually contains " + fileList.length);
        Path extractorPath = mvnDependenciesDirPath.resolve(fileList[0].getName());
        // Look for the '.jar' file inside the extractor directory.
        fileList = extractorPath.toFile().listFiles();
        for (File file : fileList) {
            if (file.getName().endsWith(".jar")) {
                return file.lastModified();
            }
        }
        fail();
        return 0;
    }

    /**
     * Configure two JFrog CLI tools, one with Releases installer and one with Artifactory installer, and test functionality for both.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testCombineReleasesAndArtifactoryTools(JenkinsRule jenkins) throws Exception {
        setupPipelineTest(jenkins);
        // Download the latest CLI version from releases.io and from Artifactory.
        configureJfrogCliFromReleases(StringUtils.EMPTY, false);
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), false);
        runPipeline(jenkins, "basic_commands");
        runPipeline(jenkins, "basic_commands_2");
    }

    /**
     * Configure two JFrog CLI tools, one with Releases installer and one with Artifactory installer, and test functionality for both.
     *
     * @param jenkins Jenkins instance injected automatically.
     */
    @Test
    public void testCombineReleasesAndArtifactoryToolsDifferentOrder(JenkinsRule jenkins) throws Exception {
        setupPipelineTest(jenkins);
        // Download the latest CLI version from Artifactory and then a specific version from releases.io.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), false);
        configureJfrogCliFromReleases(jfrogCliTestVersion, false);
        runPipeline(jenkins, "basic_commands");
        runPipeline(jenkins, "basic_commands_2");
    }
}