package io.jenkins.plugins.jfrog.integration;

import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrogInstallationTest extends PipelineTestBase {
    // Jfrog CLI version which is accessible for all operating systems
    public static final String jfrogCliTestVersion = "2.29.2";

    /**
     * Download Jfrog CLI from 'releases.io' and adds it as a global tool.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testJfrogCliInstallationFromReleases(JenkinsRule jenkins) throws Exception{
        initPipelineTest(jenkins);
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
        initPipelineTest(jenkins);
        // Download the latest CLI version.
        // TODO: add the timestemp to the
        configureJfrogCliFromArtifactory(JFROG_CLI_TOOL_NAME, "serverId", "jenkins-jfrog-tests-cli-remote-"+currentTime);
        WorkflowRun job = runPipeline(jenkins, "basic_verify_version");
        System.out.println(job.getLog());
        assertTrue(job.getLog().contains("jf version "));
    }




}

