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

class JfrogInstallationTest extends PipelineTestBase {
    // Jfrog CLI version which is accessible for all operating systems
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
        System.out.println(job.getLog());
        assertTrue(job.getLog().contains("jf version "+jfrogCliTestVersion));
        // Download the latest CLI version.
        configureJfrogCliFromReleases(JFROG_CLI_TOOL_NAME, StringUtils.EMPTY);
        job = runPipeline(jenkins, "basic_verify_version");
        System.out.println(job.getLog());
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
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME, "serverId", getRepoKey(TestRepository.CLI_REMOTE_REPO));
        WorkflowRun job = runPipeline(jenkins, "basic_verify_version");
        System.out.println(job.getLog());
        assertTrue(job.getLog().contains("jf version "));
    }

    /**
     * Check that only one copy of JFrog CLI is being downloaded to the expected location.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testDownloadingJFrogCliOnce(JenkinsRule jenkins) throws Exception{
        initPipelineTest(jenkins);
        // Run job for the first time
        WorkflowRun job = runPipeline(jenkins, "basic_verify_version");
        System.out.println(job.getLog());
        long lastModified = getCliLastModified(jenkins);
        // Run job for the second time
        job = runPipeline(jenkins, "basic_verify_version");
        System.out.println(job.getLog());
        // Verify binary wasn't modified
        assertEquals(lastModified, getCliLastModified(jenkins));
    }

    /**
     * Check that only one copy of xray's indexer is being downloaded to the expected location by using xray scan command.
     * @param jenkins Jenkins instance Injected automatically.
     */
   // @Test
    public void testDownloadingXrayIndexer(JenkinsRule jenkins) throws Exception{
        initPipelineTest(jenkins);
        WorkflowRun job = runPipeline(jenkins, "mvn_command");
        System.out.println(job.getLog());
        Path dependenciesDirPath = getJfrogInstallationDir(jenkins).resolve(JfrogDependenciesDirName).resolve("xray-indexer");
        //long lastModified = getIndexerLastModified(dependenciesDirPath);
    }

    private Path getJfrogInstallationDir(JenkinsRule jenkins) {
        return jenkins.jenkins.getRootDir().toPath().resolve("tools").resolve("io.jenkins.plugins.jfrog.JfrogInstallation");
    }
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

    private long getIndexerLastModified(Path dependenciesDirPath) {
        File[] fileList = dependenciesDirPath.toFile().listFiles();
        Path indexerPath = dependenciesDirPath;
        for (File file: fileList) {
            if (file.getName().equals("temp")) {
                continue;
            }
            String indexer = "indexer-app";
            if (!jenkins.createLocalLauncher().isUnix()) {
                indexer = indexer + ".exe";
            }
            indexerPath = indexerPath.resolve(file.getName()).resolve(indexer);
        }
        assertTrue(indexerPath.toFile().exists());
        return indexerPath.toFile().lastModified();
    }
}

