package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.*;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author yahavi
 **/
@WithJenkins
class CliEnvConfiguratorTest {

    protected JenkinsRule jenkinsRule;
    protected JenkinsProxyConfiguration proxyConfiguration;
    protected EnvVars envVars;

    @TempDir
    protected File tempFolder;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        jenkinsRule = rule;
        envVars = new EnvVars();
        envVars.put("JOB_NAME", "buildName");
        envVars.put("BUILD_NUMBER", "1");
        envVars.put("BUILD_URL", "https://acme.jenkins.io");
    }

    @Test
    public void configureCliEnvBasicTest() throws IOException, InterruptedException {
        File jfrogHomeDir = tempFolder.newFolder("jfrog-home");
        FilePath jfrogHomeTempDir = new FilePath(jfrogHomeDir);
        invokeConfigureCliEnv(jfrogHomeTempDir, new JFrogCliConfigEncryption(envVars));
        assertEnv(envVars, JFROG_CLI_BUILD_NAME, "buildName");
        assertEnv(envVars, JFROG_CLI_BUILD_NUMBER, "1");
        assertEnv(envVars, JFROG_CLI_BUILD_URL, "https://acme.jenkins.io");
        assertEnv(envVars, JFROG_CLI_HOME_DIR, jfrogHomeDir.getAbsolutePath());
    }

    @Test
    public void configEncryptionTest() throws IOException, InterruptedException {
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        assertTrue(configEncryption.shouldEncrypt());
        assertEquals(32, configEncryption.getKey().length());

        File jfrogHomeDir = tempFolder.newFolder("jfrog-home-enc");
        FilePath jfrogHomeTempDir = new FilePath(jfrogHomeDir);
        invokeConfigureCliEnv(jfrogHomeTempDir, configEncryption);
        // The encryption key file is created in jfrogHomeTempDir/encryption/ to work in Docker containers
        String keyFilePath = envVars.get(JFROG_CLI_ENCRYPTION_KEY);
        assertNotNull(keyFilePath);
        assertTrue(keyFilePath.startsWith(jfrogHomeDir.getAbsolutePath()));
        assertTrue(keyFilePath.contains("encryption"));
        assertTrue(keyFilePath.endsWith(".key"));
        assertEquals(keyFilePath, configEncryption.getKeyFilePath());
    }

    @Test
    public void configEncryptionWithHomeDirTest() throws IOException, InterruptedException {
        // Config JFROG_CLI_HOME_DIR to disable key encryption
        envVars.put(JFROG_CLI_HOME_DIR, "/a/b/c");
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        File emptyDir = tempFolder.newFolder("empty");
        invokeConfigureCliEnv(new FilePath(emptyDir), configEncryption);

        assertFalse(configEncryption.shouldEncrypt());
        assertFalse(envVars.containsKey(JFROG_CLI_ENCRYPTION_KEY));
    }

    void assertEnv(EnvVars envVars, String key, String expectedValue) {
        assertEquals(expectedValue, envVars.get(key));
    }

    void invokeConfigureCliEnv() throws IOException, InterruptedException {
        File emptyDir = tempFolder.newFolder("default");
        this.invokeConfigureCliEnv(new FilePath(emptyDir), new JFrogCliConfigEncryption(envVars));
    }

    void invokeConfigureCliEnv(FilePath jfrogHomeTempDir, JFrogCliConfigEncryption configEncryption) throws IOException, InterruptedException {
        setProxyConfiguration();
        configureCliEnv(envVars, jfrogHomeTempDir, configEncryption);
    }

    private void setProxyConfiguration() {
        hudson.ProxyConfiguration jenkinsProxyConfiguration = null;
        if (proxyConfiguration != null) {
            jenkinsProxyConfiguration = new hudson.ProxyConfiguration(proxyConfiguration.host, proxyConfiguration.port,
                    proxyConfiguration.username, proxyConfiguration.password, proxyConfiguration.noProxy);
        }
        Jenkins.get().setProxy(jenkinsProxyConfiguration);
    }
}
