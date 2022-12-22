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

class JFrogInstallationTest extends PipelineTestBase {
    // JFrog CLI version which is accessible for all operating systems
    public static final String jfrogCliTestVersion = "2.29.2";

    /**
     * Download Jfrog CLI from 'releases.io' and adds it as a global tool.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testJfrogCliInstallationFromReleases(JenkinsRule jenkins) throws Exception{
        setupPipelineTest(jenkins);
        // Download specific CLI version.
        configureJfrogCliFromReleases(JFROG_CLI_TOOL_NAME, jfrogCliTestVersion);
        WorkflowRun job = runPipeline(jenkins, "basic_verify_version");
        assertTrue(job.getLog().contains("jf version "+jfrogCliTestVersion));
        // Download the latest CLI version.
        configureJfrogCliFromReleases(JFROG_CLI_TOOL_NAME, StringUtils.EMPTY);
        job = runPipeline(jenkins, "basic_verify_version");
        // Verify newer version was installed.
        assertFalse(job.getLog().contains("jf version "+jfrogCliTestVersion));
    }

    /**
     * Download Jfrog CLI from client Artifactory and adds it as a global tool.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testJfrogCliInstallationFromArtifactory(JenkinsRule jenkins) throws Exception{
        setupPipelineTest(jenkins);
        // Download the latest CLI version.
        // Using remote repository to 'releases.io' in the client's Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO));
        WorkflowRun job = runPipeline(jenkins, "basic_verify_version");
        assertTrue(job.getLog().contains("jf version "));
    }

    private Path getJfrogInstallationDir(JenkinsRule jenkins) {
        return jenkins.jenkins.getRootDir().toPath().resolve("tools").resolve("io.jenkins.plugins.jfrog.JfrogInstallation");
    }

    /**
     * Check that only one copy of JFrog CLI is being downloaded to the expected location.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testDownloadingJFrogCliOnce(JenkinsRule jenkins) throws Exception{
        initPipelineTest(jenkins);
        // After running job for the first time, CLI's binary should be downloaded.
        runPipeline(jenkins, "basic_verify_version");
        long lastModified = getCliLastModified(jenkins);
        // Rerunning the job and verifying that the binary was not downloaded again by comparing the modification time.
        runPipeline(jenkins, "basic_verify_version");
        assertEquals(lastModified, getCliLastModified(jenkins));
    }

    /**
     * Finds the binary file in the CLI tool directory and returns its modification time.
     * @param jenkins Jenkins instance Injected automatically.
     * @return binary modification time.
     */
    private long getCliLastModified(JenkinsRule jenkins) {
        Path toolDirPath = getJfrogInstallationDir(jenkins);
        Path indexerPath = toolDirPath;
        String binary = BINARY_NAME;
        if (!jenkins.createLocalLauncher().isUnix()) {
           binary = binary + ".exe";
        }
        indexerPath = indexerPath.resolve(JFROG_CLI_TOOL_NAME).resolve(binary);
        assertTrue(indexerPath.toFile().exists());
        return indexerPath.toFile().lastModified();
    }

    /**
     * Check that only one copy of maven extractor is being downloaded to the expected 'dependencies' directory by running a script with a maven command.
     * @param jenkins Jenkins instance Injected automatically.
     */
    //
    @Test
    public void testDownloadingMavenExtractor(JenkinsRule jenkins) throws Exception{
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
     * Finds the extractor jar in the mvn extractor directory and returns its modification time.
     * @param mvnDependenciesDirPath maven's extractors directory inside the dependencies' directory.
     * @return jar modification time.
     */
    private long getExtractorLastModified(Path mvnDependenciesDirPath) {
        File[] fileList = mvnDependenciesDirPath.toFile().listFiles();
        assertEquals(1, fileList.length, "The Maven dependencies directory is expected to contain only one extractor version, but it contains " + fileList.length);
        Path extractorPath = mvnDependenciesDirPath.resolve(fileList[0].getName());
        // Look for the '.jar' file inside the extractor directory.
        fileList = extractorPath.toFile().listFiles();
        for (File file: fileList) {
            if (file.getName().endsWith(".jar")) {
                return file.lastModified();
            }
        }
        fail();
        return 0;
    }

    /**
     * Configure two JFrog CLI tools, one with Releases installer and one with Artifactory installer, and test functionality for both.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testCombineReleasesAndArtifactoryTools(JenkinsRule jenkins) throws Exception{
        setupPipelineTest(jenkins);
        // Download the latest CLI version from releases.io and then from Artifactory.
        configureJfrogCliFromReleases(JFROG_CLI_TOOL_NAME, StringUtils.EMPTY);
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO));
        runPipeline(jenkins, "basic_commands");
        runPipeline(jenkins, "basic_commands_2");
    }

    /**
     * Configure two JFrog CLI tools, one with Releases installer and one with Artifactory installer, and test functionality for both.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testCombineReleasesAndArtifactoryToolsDifferentOrder(JenkinsRule jenkins) throws Exception{
        setupPipelineTest(jenkins);
        // Download the latest CLI version from Artifactory and then a specific version from releases.io.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO));
        configureJfrogCliFromReleases(JFROG_CLI_TOOL_NAME, jfrogCliTestVersion);
        runPipeline(jenkins, "basic_commands");
        runPipeline(jenkins, "basic_commands_2");
    }
}

