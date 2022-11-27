package io.jenkins.plugins.jfrog.integration;

import hudson.FilePath;
import hudson.model.Saveable;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolProperty;
import hudson.tools.ToolPropertyDescriptor;
import hudson.util.DescribableList;
import io.jenkins.plugins.jfrog.JfrogInstallation;
import io.jenkins.plugins.jfrog.ReleasesInstaller;
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
        JfrogInstallation jf = configureJfrogCli();
        WorkflowRun job = runPipeline(jenkins, "basic");
        System.out.println(job.getLog());
        assertTrue(job.getLog().contains("jf version "+jfrogCliTestVersion));
        // remove, only for testing
        // while (true) ;
    }
    public static JfrogInstallation configureJfrogCli() throws IOException {
        Saveable NOOP = () -> {
        };
        DescribableList<ToolProperty<?>, ToolPropertyDescriptor> r = new DescribableList<>(NOOP);
        List<ReleasesInstaller> installers = new ArrayList<>();
        installers.add(new ReleasesInstaller(jfrogCliTestVersion));
        r.add(new InstallSourceProperty(installers));
        JfrogInstallation jf = new JfrogInstallation("jfrog-cli", "", r);
        Jenkins.get().getDescriptorByType(JfrogInstallation.Descriptor.class).setInstallations(jf);
        return jf;
    }


}

