package io.jenkins.plugins.jfrog;

import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import org.junit.Before;
import org.junit.Test;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.*;
import static org.junit.Assert.assertNull;


/**
 * @author yahavi
 **/
@SuppressWarnings("HttpUrlsUsage")
public class CliEnvConfiguratorProxyTest extends CliEnvConfiguratorTest {

    @Before
    public void setUp() {
        super.setUp();
        proxyConfiguration = new JenkinsProxyConfiguration();
        proxyConfiguration.host = "acme.proxy.io";
    }

    @Test
    public void configureCliEnvHttpProxyTest() {
        proxyConfiguration.port = 80;
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://acme.proxy.io:80");
        assertEnv(envVars, HTTPS_PROXY_ENV, "http://acme.proxy.io:80");
        assertNull(envVars.get(JFROG_CLI_ENV_EXCLUDE));
    }

    @Test
    public void configureCliEnvHttpsProxyTest() {
        proxyConfiguration.port = 443;
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "https://acme.proxy.io:443");
        assertEnv(envVars, HTTPS_PROXY_ENV, "https://acme.proxy.io:443");
        assertNull(envVars.get(JFROG_CLI_ENV_EXCLUDE));
    }

    @Test
    public void configureCliEnvHttpProxyAuthTest() {
        proxyConfiguration.port = 80;
        proxyConfiguration.username = "andor";
        proxyConfiguration.password = "RogueOne";
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://andor:RogueOne@acme.proxy.io:80");
        assertEnv(envVars, HTTPS_PROXY_ENV, "http://andor:RogueOne@acme.proxy.io:80");
        assertEnv(envVars, JFROG_CLI_ENV_EXCLUDE, String.join(";", JFROG_CLI_DEFAULT_EXCLUSIONS, HTTP_PROXY_ENV, HTTPS_PROXY_ENV));
    }

    @Test
    public void configureCliEnvHttpsProxyAuthTest() {
        proxyConfiguration.port = 443;
        proxyConfiguration.username = "andor";
        proxyConfiguration.password = "RogueOne";
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "https://andor:RogueOne@acme.proxy.io:443");
        assertEnv(envVars, HTTPS_PROXY_ENV, "https://andor:RogueOne@acme.proxy.io:443");
        assertEnv(envVars, JFROG_CLI_ENV_EXCLUDE, String.join(";", JFROG_CLI_DEFAULT_EXCLUSIONS, HTTP_PROXY_ENV, HTTPS_PROXY_ENV));
    }

    @Test
    public void configureCliEnvNoOverrideHttpTest() {
        envVars.put(HTTP_PROXY_ENV, "http://acme2.proxy.io:777");
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://acme2.proxy.io:777");
    }

    @Test
    public void configureCliEnvNoOverrideTest() {
        envVars.put(HTTP_PROXY_ENV, "http://acme2.proxy.io:80");
        envVars.put(HTTPS_PROXY_ENV, "http://acme2.proxy.io:443");
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://acme2.proxy.io:80");
        assertEnv(envVars, HTTPS_PROXY_ENV, "http://acme2.proxy.io:443");
    }
}
