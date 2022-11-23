package io.jenkins.plugins.jfrog.pipeline;

import hudson.model.Label;
import hudson.model.Slave;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.client.access.AccessManager;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.build.extractor.clientConfiguration.client.distribution.DistributionManager;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.Assert.fail;

import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.jfrog.artifactory.client.ArtifactoryRequest;
import org.jfrog.artifactory.client.impl.ArtifactoryRequestImpl;

@EnableJenkins
public class PipelineTestBase {

    static ArtifactoryManager artifactoryManager;

    static Artifactory artifactoryClient;
    public JenkinsRule jenkins;
    public Slave slave;
    public static final String SLAVE_LABEL = "TestSlave";
    private static final String PLATFORM_URL = System.getenv("JENKINS_PLATFORM_URL");
    public static final String ARTIFACTORY_URL = StringUtils.removeEnd(PLATFORM_URL, "/") + "/artifactory";
    public static final String ARTIFACTORY_USERNAME = System.getenv("JENKINS_PLATFORM_USERNAME");
    public static final String ACCESS_TOKEN = System.getenv("JENKINS_PLATFORM_ADMIN_TOKEN");

    public void initPipelineTest(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        createSlave(jenkins);
    }

    /**
     * Creates build-info and Artifactory Java clients.
     */
    private static void createClients() {
        artifactoryManager = new ArtifactoryManager(ARTIFACTORY_URL, ACCESS_TOKEN, new NullLog());
        artifactoryClient = ArtifactoryClientBuilder.create()
                .setUrl(ARTIFACTORY_URL)
                .setAccessToken(ACCESS_TOKEN)
                .build();
    }

    private void createSlave(JenkinsRule jenkins) {
        if (slave != null) {
            return;
        }
        try {
            slave = jenkins.createOnlineSlave(Label.get(SLAVE_LABEL));
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
    }
    @BeforeClass
    public static void setUp() {
        //currentTime = System.currentTimeMillis();
       // verifyEnvironment();
        //createSlave();
        //setEnvVars();
        createClients();
//        setGlobalConfiguration();
//        cleanUpArtifactory(artifactoryClient);
//        createPipelineSubstitution();
//        // Create repositories
//        Arrays.stream(TestRepository.values()).forEach(PipelineTestBase::createRepo);
//        createProject();
    }
}
