package io.jenkins.plugins.jfrog;

import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrogBuildInfoPublisherTest {

    @Test
    void isApplicableRemainsAvailableOnMavenProjectJobs() throws Exception {
        JfrogBuildInfoPublisher.DescriptorImpl descriptor = new JfrogBuildInfoPublisher.DescriptorImpl();

        // Upgrade path: existing Maven jobs that still use this publisher must keep seeing it.
        assertTrue(descriptor.isApplicable(mavenModuleSetClass()));
        assertTrue(descriptor.isApplicable(FreeStyleProject.class));
    }

    @Test
    void detectsMavenModuleSetAndModuleByClassName() throws Exception {
        assertTrue(JfrogBuildInfoPublisher.isMavenProjectJob(mavenModuleSetClass()));
        assertTrue(JfrogBuildInfoPublisher.isMavenProjectJob(mavenModuleClass()));
        assertFalse(JfrogBuildInfoPublisher.isMavenProjectJob(FreeStyleProject.class));
        assertFalse(JfrogBuildInfoPublisher.isMavenProjectJob(null));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AbstractProject> mavenModuleSetClass() throws ClassNotFoundException {
        // Load without initializing the descriptor, which needs a running Jenkins.
        return (Class<? extends AbstractProject>) Class.forName(
                "hudson.maven.MavenModuleSet", false, JfrogBuildInfoPublisherTest.class.getClassLoader());
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AbstractProject> mavenModuleClass() throws ClassNotFoundException {
        return (Class<? extends AbstractProject>) Class.forName(
                "hudson.maven.MavenModule", false, JfrogBuildInfoPublisherTest.class.getClassLoader());
    }
}
