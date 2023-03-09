package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import org.jfrog.build.client.ProxyConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.*;
import static org.junit.Assert.assertEquals;


/**
 * @author yahavi
 **/
public class CliEnvConfiguratorTest {
    ProxyConfiguration proxyConfiguration;
    EnvVars envVars;

    @Before
    public void setUp() {
        envVars = new EnvVars();
        envVars.put("JOB_NAME", "buildName");
        envVars.put("BUILD_NUMBER", "1");
        envVars.put("BUILD_URL", "https://acme.jenkins.io");
    }

    @Test
    public void configureCliEnvBasicTest() {
        invokeConfigureCliEnv("a/b/c");
        assertEnv(envVars, JFROG_CLI_BUILD_NAME, "buildName");
        assertEnv(envVars, JFROG_CLI_BUILD_NUMBER, "1");
        assertEnv(envVars, JFROG_CLI_BUILD_URL, "https://acme.jenkins.io");
        assertEnv(envVars, JFROG_CLI_HOME_DIR, "a/b/c");
    }

    void assertEnv(EnvVars envVars, String key, String expectedValue) {
        assertEquals(expectedValue, envVars.get(key));
    }

    void invokeConfigureCliEnv() {
        this.invokeConfigureCliEnv("");
    }

    void invokeConfigureCliEnv(String jfrogHomeTempDir) {
        try (MockedStatic<Utils> mockController = Mockito.mockStatic(Utils.class)) {
            mockController.when(Utils::createProxyConfiguration).thenReturn(proxyConfiguration);
            configureCliEnv(envVars, jfrogHomeTempDir);
        }
    }
}
