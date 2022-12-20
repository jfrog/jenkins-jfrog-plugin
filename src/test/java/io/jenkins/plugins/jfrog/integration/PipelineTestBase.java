package io.jenkins.plugins.jfrog.integration;

import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.FilePath;
import hudson.model.Label;
import hudson.model.ModelObject;
import hudson.model.Saveable;
import hudson.model.Slave;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolProperty;
import hudson.tools.ToolPropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.ArtifactoryInstaller;
import io.jenkins.plugins.jfrog.BinaryInstaller;
import io.jenkins.plugins.jfrog.JfrogInstallation;
import io.jenkins.plugins.jfrog.ReleasesInstaller;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.text.StringSubstitutor;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jfrog.artifactory.client.ArtifactoryResponse;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.jfrog.artifactory.client.ArtifactoryRequest;
import org.jfrog.artifactory.client.impl.ArtifactoryRequestImpl;

import static org.junit.Assert.*;

@EnableJenkins
public class PipelineTestBase {
    public static long currentTime;

    static Artifactory artifactoryClient;
    public static JenkinsRule jenkins;
    public static Slave slave;
    private static StringSubstitutor pipelineSubstitution;
    public static final String SLAVE_LABEL = "TestSlave";
    public static final String PLATFORM_URL = System.getenv("JENKINS_PLATFORM_URL");
    public static final String ARTIFACTORY_URL = StringUtils.removeEnd(PLATFORM_URL, "/") + "/artifactory";
    public static final String ARTIFACTORY_USERNAME = System.getenv("JENKINS_PLATFORM_USERNAME");
    public static final String ARTIFACTORY_PASSWORD = System.getenv("JENKINS_ARTIFACTORY_PASSWORD");
    public static final String ACCESS_TOKEN = System.getenv("JENKINS_PLATFORM_ADMIN_TOKEN");
    private static final Path INTEGRATION_BASE_PATH = Paths.get(".").toAbsolutePath().normalize()
            .resolve(Paths.get("src", "test", "resources", "integration"));
    public static final String JFROG_CLI_TOOL_NAME = "jfrog-cli";

    public void initPipelineTest(JenkinsRule jenkins) throws IOException {
        setupPipelineTest(jenkins);
        // Download the latest CLI version.
        configureJfrogCliFromReleases(JFROG_CLI_TOOL_NAME, StringUtils.EMPTY);
    }

    // Set up test's environment
    public void setupPipelineTest(JenkinsRule jenkins) throws IOException {
        this.jenkins = jenkins;
        setUp();
    }

    /**
     * Creates build-info and Artifactory Java clients.
     */
    private static void createClients() {
        artifactoryClient = ArtifactoryClientBuilder.create()
                .setUrl(ARTIFACTORY_URL)
                .setUsername(ARTIFACTORY_USERNAME)
                .setPassword(ARTIFACTORY_PASSWORD)
                .setAccessToken(ACCESS_TOKEN)
                .build();
    }

    private static void createSlave() {
        try {
            slave = jenkins.createOnlineSlave(Label.get(SLAVE_LABEL));
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
    }

    @BeforeClass
    public static void setUp() throws IOException {
        currentTime = System.currentTimeMillis();
        verifyEnvironment();
        createSlave();
        createClients();
        setGlobalConfiguration();
        createPipelineSubstitution();
        // Create repositories
        Arrays.stream(TestRepository.values()).forEach(PipelineTestBase::createRepo);
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
        project.setDefinition(new CpsFlowDefinition(readPipeline(name), false));
        return jenkins.buildAndAssertSuccess(project);
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
        if (StringUtils.isBlank(ACCESS_TOKEN) && StringUtils.isBlank(ARTIFACTORY_PASSWORD)) {
            throw new IllegalArgumentException("JENKINS_PLATFORM_ADMIN_TOKEN or JENKINS_PLATFORM_PASSWORD are not set");
        }
    }

    /**
     * Create JFrog server in the Global configuration.
     */
    private static void setGlobalConfiguration() throws IOException {
        JFrogPlatformBuilder.DescriptorImpl jfrogBuilder = (JFrogPlatformBuilder.DescriptorImpl) jenkins.getInstance().getDescriptor(JFrogPlatformBuilder.class);
        Assert.assertNotNull(jfrogBuilder);
        CredentialsConfig platformCred = new CredentialsConfig(Secret.fromString(ARTIFACTORY_USERNAME), Secret.fromString(ARTIFACTORY_PASSWORD), Secret.fromString(ACCESS_TOKEN), "credentials");
        List<JFrogPlatformInstance> artifactoryServers = new ArrayList<JFrogPlatformInstance>() {{
            add(new JFrogPlatformInstance("serverId", PLATFORM_URL,  platformCred, ARTIFACTORY_URL, "",""));
        }};
        jfrogBuilder.setJfrogInstances(artifactoryServers);
        Jenkins.get().getDescriptorByType(JFrogPlatformBuilder.DescriptorImpl.class).setJfrogInstances(artifactoryServers);
        CredentialsStore store = lookupStore(jenkins);
        addCreds(store, CredentialsScope.GLOBAL, "credentials");
    }

    private static void addCreds(CredentialsStore store, CredentialsScope scope, String id) throws IOException {
        // For purposes of this test we do not care about domains.
        store.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(scope, id, null, ARTIFACTORY_USERNAME, ARTIFACTORY_PASSWORD));
    }

    private static CredentialsStore lookupStore(ModelObject object) {
        Iterator<CredentialsStore> stores = CredentialsProvider.lookupStores(object).iterator();
        assertTrue(stores.hasNext());
        CredentialsStore store = stores.next();
        //assertEquals("we got the expected store", object, store.getContext());
        return store;
    }
    /**
     * Create a temporary repository for the tests.
     *
     * @param repository - The repository base name
     */
    private static void createRepo(TestRepository repository) {
        ArtifactoryResponse response = null;
        try {
            String repositorySettings = readConfigurationWithSubstitution(repository.getRepoName());
            response = artifactoryClient.restCall(new ArtifactoryRequestImpl()
                    .method(ArtifactoryRequest.Method.PUT)
                    .requestType(ArtifactoryRequest.ContentType.JSON)
                    .apiUrl("api/repositories/" + getRepoKey(repository))
                    .requestBody(repositorySettings));
        } catch (Exception e) {
            fail(ExceptionUtils.getRootCauseMessage(e));
        }
        if (!response.isSuccessResponse()) {
            fail(String.format("Failed creating repository %s: %s", getRepoKey(repository), response.getStatusLine()));
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

    /**
     * Creates string substitution for the pipelines. The tests use it to replace strings in the pipelines after
     * loading them.
     */
    private static void createPipelineSubstitution() {
        pipelineSubstitution = new StringSubstitutor(new HashMap<String, String>() {{
            put("DUMMY_FILE_PATH", fixWindowsPath(String.valueOf(INTEGRATION_BASE_PATH.resolve("files").resolve("dummyfile"))));
            put("LOCAL_REPO1", getRepoKey(TestRepository.LOCAL_REPO1));
            put("REMOTE_REPO1", getRepoKey(TestRepository.CLI_REMOTE_REPO));
            put("JFROG_CLI_TOOL_NAME", JFROG_CLI_TOOL_NAME);
        }});
    }

    /**
     * Escape backslashes in filesystem path.
     *
     * @param path - Filesystem path to fix
     * @return path compatible with Windows
     */
    static String fixWindowsPath(String path) {
        return StringUtils.replace(path, "\\", "\\\\");
    }

    /**
     * Read pipeline from 'jenkins-artifactory-plugin/src/test/resources/integration/pipelines'
     *
     * @param name - The pipeline name
     * @return pipeline as a string
     */
    private String readPipeline(String name) throws IOException {
        String pipeline = FileUtils.readFileToString(INTEGRATION_BASE_PATH
                .resolve("pipelines")
                .resolve(name + ".pipeline").toFile(), StandardCharsets.UTF_8);
        return pipelineSubstitution.replace(pipeline);
    }

    public static JfrogInstallation configureJfrogCliFromReleases(String toolName, String cliVersion) throws IOException {
        return configureJfrogCli(toolName, new ReleasesInstaller(cliVersion));
    }

    public static JfrogInstallation configureJfrogCliFromArtifactory(String toolName, String serverId, String repo) throws IOException {
        return configureJfrogCli(toolName, new ArtifactoryInstaller(serverId, repo));
    }

    public static JfrogInstallation configureJfrogCli(String toolName, BinaryInstaller installer) throws IOException {
        Saveable NOOP = () -> {
        };
        DescribableList<ToolProperty<?>, ToolPropertyDescriptor> r = new DescribableList<>(NOOP);
        List<BinaryInstaller> installers = new ArrayList<>();
        installers.add(installer);
        r.add(new InstallSourceProperty(installers));
        JfrogInstallation jf = new JfrogInstallation(toolName, "", r);
        Jenkins.get().getDescriptorByType(JfrogInstallation.Descriptor.class).setInstallations(jf);
        return jf;
    }
}
