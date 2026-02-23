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
import java.nio.file.Files;
import java.nio.file.Path;

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
    protected Path tempFolder;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        jenkinsRule = rule;
        envVars = new EnvVars();
        envVars.put("JOB_NAME", "buildName");
        envVars.put("BUILD_NUMBER", "1");
        envVars.put("BUILD_URL", "https://acme.jenkins.io");
    }

    @Test
    void configureCliEnvBasicTest() throws Exception {
        File jfrogHomeDir = Files.createTempDirectory(tempFolder, "jfrog-home").toFile();
        FilePath jfrogHomeTempDir = new FilePath(jfrogHomeDir);
        invokeConfigureCliEnv(jfrogHomeTempDir, new JFrogCliConfigEncryption(envVars));
        assertEnv(envVars, JFROG_CLI_BUILD_NAME, "buildName");
        assertEnv(envVars, JFROG_CLI_BUILD_NUMBER, "1");
        assertEnv(envVars, JFROG_CLI_BUILD_URL, "https://acme.jenkins.io");
        assertEnv(envVars, JFROG_CLI_HOME_DIR, jfrogHomeDir.getAbsolutePath());
    }

    @Test
    void configEncryptionTest() throws Exception {
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        assertTrue(configEncryption.shouldEncrypt());
        assertEquals(32, configEncryption.getKey().length());

        File jfrogHomeDir = Files.createTempDirectory(tempFolder, "jfrog-home-enc").toFile();
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
    void configEncryptionWithHomeDirTest() throws Exception {
        // Config JFROG_CLI_HOME_DIR to disable key encryption
        envVars.put(JFROG_CLI_HOME_DIR, "/a/b/c");
        JFrogCliConfigEncryption configEncryption = new JFrogCliConfigEncryption(envVars);
        File emptyDir = Files.createTempDirectory(tempFolder, "empty").toFile();
        invokeConfigureCliEnv(new FilePath(emptyDir), configEncryption);

        assertFalse(configEncryption.shouldEncrypt());
        assertFalse(envVars.containsKey(JFROG_CLI_ENCRYPTION_KEY));
    }

    static void assertEnv(EnvVars envVars, String key, String expectedValue) {
        assertEquals(expectedValue, envVars.get(key));
    }

    void invokeConfigureCliEnv() throws Exception {
        File emptyDir = Files.createTempDirectory(tempFolder, "default").toFile();
        this.invokeConfigureCliEnv(new FilePath(emptyDir), new JFrogCliConfigEncryption(envVars));
    }

    void invokeConfigureCliEnv(FilePath jfrogHomeTempDir, JFrogCliConfigEncryption configEncryption) throws Exception {
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
