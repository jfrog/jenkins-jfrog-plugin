package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

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

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        jenkinsRule = rule;
        envVars = new EnvVars();
        envVars.put("JOB_NAME", "buildName");
        envVars.put("BUILD_NUMBER", "1");
        envVars.put("BUILD_URL", "https://acme.jenkins.io");
    }

    @Test
    void configureCliEnvBasicTest() {
        invokeConfigureCliEnv("a/b/c", new JFrogCliConfigEncryption(envVars));
        assertEnv(envVars, JFROG_CLI_BUILD_NAME, "buildName");
        assertEnv(envVars, JFROG_CLI_BUILD_NUMBER, "1");
        assertEnv(envVars, JFROG_CLI_BUILD_URL, "https://acme.jenkins.io");
        assertEnv(envVars, JFROG_CLI_HOME_DIR, "a/b/c");
    }

    @Test
    void configEncryptionTest() {
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        assertTrue(configEncryption.shouldEncrypt());
        assertEquals(32, configEncryption.getKey().length());

        invokeConfigureCliEnv("a/b/c", configEncryption);
        assertEnv(envVars, JFROG_CLI_ENCRYPTION_KEY, configEncryption.getKeyOrFilePath());
    }

    @Test
    void configEncryptionWithHomeDirTest() {
        // Config JFROG_CLI_HOME_DIR to disable key encryption
        envVars.put(JFROG_CLI_HOME_DIR, "/a/b/c");
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        invokeConfigureCliEnv("", configEncryption);

        assertFalse(configEncryption.shouldEncrypt());
        assertFalse(envVars.containsKey(JFROG_CLI_ENCRYPTION_KEY));
    }

    protected static void assertEnv(EnvVars envVars, String key, String expectedValue) {
        assertEquals(expectedValue, envVars.get(key));
    }

    protected void invokeConfigureCliEnv() {
        this.invokeConfigureCliEnv("", new JFrogCliConfigEncryption(envVars));
    }

    protected void invokeConfigureCliEnv(String jfrogHomeTempDir, JFrogCliConfigEncryption configEncryption) {
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
