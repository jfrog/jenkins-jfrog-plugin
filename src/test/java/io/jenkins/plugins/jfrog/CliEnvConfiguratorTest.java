package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.*;
import static org.junit.Assert.*;


/**
 * @author yahavi
 **/
public class CliEnvConfiguratorTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    JenkinsProxyConfiguration proxyConfiguration;
    EnvVars envVars;

    @Before
    public void setUp() {
        envVars = new EnvVars();
        envVars.put("JOB_NAME", "buildName");
        envVars.put("BUILD_NUMBER", "1");
        envVars.put("BUILD_URL", "https://acme.jenkins.io");
    }

    @Test
    public void configureCliEnvBasicTest() throws IOException {
        String jfrogHomeTempDir = tempFolder.newFolder("jfrog-home").getAbsolutePath();
        invokeConfigureCliEnv(jfrogHomeTempDir, new JFrogCliConfigEncryption(envVars));
        assertEnv(envVars, JFROG_CLI_BUILD_NAME, "buildName");
        assertEnv(envVars, JFROG_CLI_BUILD_NUMBER, "1");
        assertEnv(envVars, JFROG_CLI_BUILD_URL, "https://acme.jenkins.io");
        assertEnv(envVars, JFROG_CLI_HOME_DIR, jfrogHomeTempDir);
    }

    @Test
    public void configEncryptionTest() throws IOException {
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        assertTrue(configEncryption.shouldEncrypt());
        assertEquals(32, configEncryption.getKey().length());

        String jfrogHomeTempDir = tempFolder.newFolder("jfrog-home-enc").getAbsolutePath();
        invokeConfigureCliEnv(jfrogHomeTempDir, configEncryption);
        // The encryption key file is created in jfrogHomeTempDir/encryption/ to work in Docker containers
        String keyFilePath = envVars.get(JFROG_CLI_ENCRYPTION_KEY);
        assertNotNull(keyFilePath);
        assertTrue(keyFilePath.startsWith(jfrogHomeTempDir));
        assertTrue(keyFilePath.contains("encryption"));
        assertTrue(keyFilePath.endsWith(".key"));
        assertEquals(keyFilePath, configEncryption.getKeyFilePath());
    }

    @Test
    public void configEncryptionWithHomeDirTest() throws IOException {
        // Config JFROG_CLI_HOME_DIR to disable key encryption
        envVars.put(JFROG_CLI_HOME_DIR, "/a/b/c");
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        invokeConfigureCliEnv("", configEncryption);

        assertFalse(configEncryption.shouldEncrypt());
        assertFalse(envVars.containsKey(JFROG_CLI_ENCRYPTION_KEY));
    }

    void assertEnv(EnvVars envVars, String key, String expectedValue) {
        assertEquals(expectedValue, envVars.get(key));
    }

    void invokeConfigureCliEnv() throws IOException {
        this.invokeConfigureCliEnv("", new JFrogCliConfigEncryption(envVars));
    }

    void invokeConfigureCliEnv(String jfrogHomeTempDir, JFrogCliConfigEncryption configEncryption) throws IOException {
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
