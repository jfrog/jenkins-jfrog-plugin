package io.jenkins.plugins.jfrog;

import hudson.FilePath;
import hudson.model.Saveable;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolProperty;
import hudson.tools.ToolPropertyDescriptor;
import hudson.util.DescribableList;
import io.jenkins.plugins.jfrog.pipeline.PipelineTestBase;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO un-comment tests
class JfrogInstallationTest extends PipelineTestBase {
    // Jfrog CLI version which is accessible for all operating systems
    public static final String jfrogCliTestVersion = "2.29.2";

    /**
     * Adds Jfrog cli tool as a global tool and verify installation.
     * @param jenkins Jenkins instance Injected automatically.
     */
    @Test
    public void testJfrogCliInstallation(JenkinsRule jenkins) throws Exception{
        initPipelineTest(jenkins);
        configureJfrogCli();
        WorkflowRun job = runPipeline(jenkins, "");
        assertTrue(job.getLog().contains("jf version "+jfrogCliTestVersion));
        // remove, only for testing
         //while (true) ;
    }
    public static JfrogInstallation configureJfrogCli() throws IOException {
        Saveable NOOP = () -> {
        };
        DescribableList<ToolProperty<?>, ToolPropertyDescriptor> r = new DescribableList<>(NOOP);
        List<ReleasesInstaller> installers = new ArrayList<>();
        installers.add(new ReleasesInstaller(jfrogCliTestVersion));
        r.add(new InstallSourceProperty(installers));
        JfrogInstallation jf = new JfrogInstallation("cli", "", r);
        Jenkins.get().getDescriptorByType(JfrogInstallation.Descriptor.class).setInstallations(jf);
        return jf;
    }

    /**
     * Run pipeline script.
     *
     * @param name - Pipeline name from 'jenkins-artifactory-plugin/src/test/resources/integration/pipelines'
     * @return the Jenkins job
     */
    WorkflowRun runPipeline(JenkinsRule jenkins, String name) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class);
        FilePath slaveWs = slave.getWorkspaceFor(project);
        if (slaveWs == null) {
            throw new Exception("Slave workspace not found");
        }

        slaveWs.mkdirs();
        String file = "pipeline {\n" +
                "    agent any\n" +
                "    tools {\n" +
                "        \"jfrog\" \"cli\"\n" +
                "    }\n" +
                "    stages {\n" +
                "        stage('Build') {\n" +
                "            steps {\n" +
                "                echo 'Building..'\n" +
                "                jf '-v'\n" +
                "            }\n" +
                "        }\n" +
                "     }\n" +
                "}";
        project.setDefinition(new CpsFlowDefinition(file, false));
        return jenkins.buildAndAssertSuccess(project);
    }
}

