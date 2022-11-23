package io.jenkins.plugins.jfrog.integration;

import hudson.model.Label;
import hudson.model.Slave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.apache.commons.text.StringSubstitutor;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.fail;

import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.jfrog.artifactory.client.ArtifactoryRequest;
import org.jfrog.artifactory.client.impl.ArtifactoryRequestImpl;

@EnableJenkins
public class PipelineTestBase {
    private static long currentTime;
    static ArtifactoryManager artifactoryManager;

    static Artifactory artifactoryClient;
    public static JenkinsRule jenkins;
    public static Slave slave;
    public static final String SLAVE_LABEL = "TestSlave";
    public static final String PLATFORM_URL = System.getenv("JENKINS_PLATFORM_URL");
    public static final String ARTIFACTORY_URL = StringUtils.removeEnd(PLATFORM_URL, "/") + "/artifactory";
    public static final String ARTIFACTORY_USERNAME = System.getenv("JENKINS_PLATFORM_USERNAME");
    public static final String ACCESS_TOKEN = System.getenv("JENKINS_PLATFORM_ADMIN_TOKEN");
    private static final Path INTEGRATION_BASE_PATH = Paths.get(".").toAbsolutePath().normalize()
            .resolve(Paths.get("src", "test", "resources", "integration"));

    public void initPipelineTest(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        setUp();
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

    private static void createSlave() {
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
        currentTime = System.currentTimeMillis();
        verifyEnvironment();
        createSlave();
//        setEnvVars();
        createClients();
        setGlobalConfiguration();
//        cleanUpArtifactory(artifactoryClient);
//        createPipelineSubstitution();
//        // Create repositories
          Arrays.stream(TestRepository.values()).forEach(PipelineTestBase::createRepo);
//        createProject();
    }

    /**
     * Verify ARTIFACTORY_URL, ARTIFACTORY_USERNAME and ACCESS_TOKEN
     */
    private static void verifyEnvironment() {
        if (StringUtils.isBlank(PLATFORM_URL)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_URL is not set");
        }
        if (StringUtils.isBlank(ARTIFACTORY_USERNAME)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_USERNAME is not set");
        }
        if (StringUtils.isBlank(ACCESS_TOKEN)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_ADMIN_TOKEN is not set");
        }
    }

    /**
     * Create JFrog server in the Global configuration.
     */
    private static void setGlobalConfiguration() {
        JFrogPlatformBuilder.DescriptorImpl jfrogBuilder = (JFrogPlatformBuilder.DescriptorImpl) jenkins.getInstance().getDescriptor(JFrogPlatformBuilder.class);
        Assert.assertNotNull(jfrogBuilder);
        CredentialsConfig platformCred = new CredentialsConfig(Secret.fromString(ARTIFACTORY_USERNAME), Secret.fromString(ACCESS_TOKEN), Secret.fromString(ACCESS_TOKEN), "credentials");
        List<JFrogPlatformInstance> artifactoryServers = new ArrayList<JFrogPlatformInstance>() {{
            add(new JFrogPlatformInstance("serverId", PLATFORM_URL,  platformCred, ARTIFACTORY_URL, "",""));
        }};
        jfrogBuilder.setJfrogInstances(artifactoryServers);
    }

    /**
     * Create a temporary repository for the tests.
     *
     * @param repository - The repository base name
     */
    private static void createRepo(TestRepository repository) {
        try {
            String repositorySettings = readConfigurationWithSubstitution(repository.getRepoName());
            artifactoryClient.restCall(new ArtifactoryRequestImpl()
                    .method(ArtifactoryRequest.Method.PUT)
                    .requestType(ArtifactoryRequest.ContentType.JSON)
                    .apiUrl("api/repositories/" + getRepoKey(repository))
                    .requestBody(repositorySettings));
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
    }

    /**
     * Get the repository key of the temporary test repository.
     *
     * @param repository - The repository base name
     * @return repository key of the temporary test repository
     */
    static String getRepoKey(TestRepository repository) {
        return String.format("%s-%d", repository.getRepoName(), currentTime);
    }

    /**
     * Read repository or project configuration and replace placeholders with their corresponding values.
     *
     * @param repoOrProject - Name of configuration in resources.
     * @return The configuration after substitution.
     */
    private static String readConfigurationWithSubstitution(String repoOrProject) {
        try {
            return FileUtils.readFileToString(INTEGRATION_BASE_PATH
                    .resolve("settings")
                    .resolve(repoOrProject + ".json").toFile(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
        return null;
    }
}
