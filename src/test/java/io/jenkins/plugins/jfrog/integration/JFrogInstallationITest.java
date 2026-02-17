package io.jenkins.plugins.jfrog.integration;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.jfrog.actions.BuildInfoBuildBadgeAction;
import joptsimple.internal.Strings;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.JfrogInstallation.JfrogDependenciesDirName;
import static io.jenkins.plugins.jfrog.Utils.BINARY_NAME;
import static org.junit.jupiter.api.Assertions.*;

class JFrogInstallationITest extends PipelineTestBase {
    // JFrog CLI version which is accessible for all operating systems.
    private static final String JFROG_CLI_TEST_VERSION = "2.29.2";

    /**
     * Download Jfrog CLI from 'releases.jfrog.io' and adds it as a global tool.
     */
    @Test
    void testJFrogCliInstallationFromReleases() throws Exception {
        setupJenkins();
        // Download specific CLI version.
        configureJfrogCliFromReleases(JFROG_CLI_TEST_VERSION, true);
        WorkflowRun job = runPipeline("basic_version_command");
        // Verify specific version was installed.
        assertTrue(job.getLog().contains("jf version " + JFROG_CLI_TEST_VERSION));
        // Download the latest CLI version.
        configureJfrogCliFromReleases(StringUtils.EMPTY, true);
        job = runPipeline("basic_version_command");
        // Verify newer version was installed.
        assertFalse(job.getLog().contains("jf version " + JFROG_CLI_TEST_VERSION));
    }

    /**
     * Download JFrog CLI from client Artifactory and adds it as a global tool.
     */
    @Test
    void testJFrogCliInstallationFromArtifactory() throws Exception {
        // Download the latest CLI version.
        // Using remote repository to 'releases.jfrog.io' in the client's Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_1, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), true);
        WorkflowRun job = runPipeline("basic_version_command");
        assertTrue(job.getLog().contains("jf version "));
    }

    // Gets JfrogInstallation directory in Jenkins work root.
    private Path getJfrogInstallationDir() {
        return jenkins.jenkins.getRootDir().toPath().resolve("tools").resolve("io.jenkins.plugins.jfrog.JfrogInstallation");
    }

    /**
     * Check that only one copy of JFrog CLI is being downloaded to the expected location when running multiple jobs.
     */
    @Test
    void testDownloadingJFrogCliOnce() throws Exception {
        initPipelineTest();
        // After running job for the first time, CLI's binary should be downloaded.
        runPipeline("basic_version_command");
        long lastModified = getCliLastModified();
        // Rerunning the job and verifying that the binary was not downloaded again by comparing the modification time.
        runPipeline("basic_version_command");
        assertEquals(lastModified, getCliLastModified());
    }

    /**
     * Finds the binary file in the CLI tool directory and returns its modification time.
     * @return binary's modification time.
     */
    private long getCliLastModified() {
        Path binaryPath = getJfrogInstallationDir();
        String name = BINARY_NAME;
        if (!jenkins.createLocalLauncher().isUnix()) {
            name += ".exe";
        }
        binaryPath = binaryPath.resolve(JFROG_CLI_TOOL_NAME_1).resolve(name);
        assertTrue(Files.exists(binaryPath));
        return binaryPath.toFile().lastModified();
    }

    /**
     * Check that only one copy of the Maven extractor is downloaded to the expected 'dependencies' directory.
     */
    @Test
    void testDownloadingMavenExtractor() throws Exception {
        initPipelineTest();
        // After running job for the first time, Mvn extractor should be downloaded.
        runPipeline("mvn_command");
        Path mvnDependenciesDirPath = getJfrogInstallationDir().resolve(JfrogDependenciesDirName).resolve("maven");
        long lastModified = getExtractorLastModified(mvnDependenciesDirPath);
        // Rerunning the job and verifying that the extractor was not downloaded again by comparing the modification time.
        runPipeline("mvn_command");
        assertEquals(lastModified, getExtractorLastModified(mvnDependenciesDirPath));
    }

    /**
     * Finds the extractor jar in the mvn extractor directory and returns its modification time.
     *
     * @param mvnDependenciesDirPath maven's extractors directory inside the dependencies' directory.
     * @return extractor jar modification time.
     */
    private long getExtractorLastModified(Path mvnDependenciesDirPath) throws IOException {
        File[] fileList = mvnDependenciesDirPath.toFile().listFiles();
        assertNotNull(fileList);
        assertEquals(1, fileList.length, "The Maven dependencies directory is expected to contain only one extractor version, but it actually contains " + fileList.length);
        Path extractorPath = mvnDependenciesDirPath.resolve(fileList[0].getName());
        // Look for the '.jar' file inside the extractor directory.
        try (Stream<Path> mavenExtractorFiles = Files.list(extractorPath)) {
            Path jar = mavenExtractorFiles.filter(file -> file.toString().endsWith(".jar")).findFirst().orElse(null);
            assertNotNull(jar, "Couldn't find the build-info-extractor-maven3 jar in " + extractorPath);
            return Files.getLastModifiedTime(jar).toMillis();
        }
    }

    /**
     * Configure two JFrog CLI tools, one with Releases installer and one with Artifactory installer, and test functionality for both.
     */
    @Test
    void testCombineReleasesAndArtifactoryTools() throws Exception {
        setupJenkins();
        // Download the latest CLI version from releases.jfrog.io.
        configureJfrogCliFromReleases(StringUtils.EMPTY, false);
        // Download the latest CLI version from Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), false);
        runPipeline("basic_commands");
        runPipeline("basic_commands_2");
    }

    /**
     * Configure two JFrog CLI tools, one with Releases installer and one with Artifactory installer, and test functionality for both.
     */
    @Test
    void testCombineReleasesAndArtifactoryToolsDifferentOrder() throws Exception {
        setupJenkins();
        // Download the latest CLI version from Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), false);
        // Download a specific CLI version from releases.jfrog.io.
        configureJfrogCliFromReleases(JFROG_CLI_TEST_VERSION, false);
        // Running CLI installed from releases.jfrog.io and verify specific version was installed.
        WorkflowRun job = runPipeline("basic_commands");
        assertTrue(job.getLog().contains("jf version " + JFROG_CLI_TEST_VERSION));
        // Running CLI installed from Artifactory and verify the latest version was installed.
        job = runPipeline("basic_commands_2");
        assertFalse(job.getLog().contains("jf version " + JFROG_CLI_TEST_VERSION));
    }

    /**
     * Configure JFrog CLI tool and test functionality for the build-info action.
     */
    @Test
    void testBuildInfoAction() throws Exception {
        setupJenkins();
        // Download a specific CLI version from releases.jfrog.io.
        configureJfrogCliFromReleases(Strings.EMPTY, false);
        // Running CLI installed from releases.jfrog.io and verify specific version was installed.
        WorkflowRun job = runPipeline("build_info");
        // Assert build info published
        assertTrue(job.getLog().contains("Build info successfully deployed"));
        // Check build-info Action
        BuildInfoBuildBadgeAction buildInfoBuildBadgeAction = job.getAction(BuildInfoBuildBadgeAction.class);
        assertNotNull(buildInfoBuildBadgeAction);
        assertTrue(StringUtils.startsWith(buildInfoBuildBadgeAction.getUrlName(), PLATFORM_URL));
        assertTrue(StringUtils.isNotBlank(buildInfoBuildBadgeAction.getIconFileName()));
        assertEquals("Artifactory Build Info", buildInfoBuildBadgeAction.getDisplayName());
    }

    /**
     * Test the functionality of providing arguments as list: jf (['rt', 'ping'])
     */
    @Test
    void testArgList() throws Exception {
        setupJenkins();
        // Download the latest CLI version from releases.jfrog.io.
        configureJfrogCliFromReleases(StringUtils.EMPTY, false);
        // Download the latest CLI version from Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_1, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), false);
        runPipeline("basic_commands_array");
    }

    @Test
    void testConfigurationAsCode() throws Exception {
        setupJenkins();
        // Download the latest CLI version from Artifactory.
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME_2, TEST_CONFIGURED_SERVER_ID, getRepoKey(TestRepository.CLI_REMOTE_REPO), false);
        // Download a specific CLI version from releases.jfrog.io.
        configureJfrogCliFromReleases(JFROG_CLI_TEST_VERSION, false);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            // Export configuration to a local variable
            ConfigurationAsCode.get().export(outputStream);
            String output = outputStream.toString();

            // Assert no errors
            assertFalse(StringUtils.containsIgnoreCase(output, "failed to export"));

            // Assert that jFrogPlatformBuilder and jfrog tags exist in the export
            assertTrue(StringUtils.containsIgnoreCase(output, "jFrogPlatformBuilder:"));
            assertTrue(StringUtils.containsIgnoreCase(output, "jfrog:"));
        }
    }

    @Test
    void testOutput() throws Exception {
        setupJenkins();

        // Download the latest CLI version from releases.jfrog.io.
        configureJfrogCliFromReleases(StringUtils.EMPTY, false);

        // Run pipeline that asserts the output is correct
        runPipeline("version_output");
    }
}
