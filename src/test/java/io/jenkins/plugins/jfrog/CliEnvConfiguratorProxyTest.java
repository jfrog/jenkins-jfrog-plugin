package io.jenkins.plugins.jfrog;

import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.*;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * @author yahavi
 **/
@SuppressWarnings("HttpUrlsUsage")
class CliEnvConfiguratorProxyTest extends CliEnvConfiguratorTest {

    @BeforeEach
    void beforeEach() {
        proxyConfiguration = new JenkinsProxyConfiguration();
        proxyConfiguration.host = "acme.proxy.io";
    }

    @Test
    void configureCliEnvHttpProxyTest() {
        proxyConfiguration.port = 80;
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://acme.proxy.io:80");
        assertEnv(envVars, HTTPS_PROXY_ENV, "http://acme.proxy.io:80");
        assertNull(envVars.get(JFROG_CLI_ENV_EXCLUDE));
    }

    @Test
    void configureCliEnvHttpsProxyTest() {
        proxyConfiguration.port = 443;
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "https://acme.proxy.io:443");
        assertEnv(envVars, HTTPS_PROXY_ENV, "https://acme.proxy.io:443");
        assertNull(envVars.get(JFROG_CLI_ENV_EXCLUDE));
    }

    @Test
    void configureCliEnvHttpProxyAuthTest() {
        proxyConfiguration.port = 80;
        proxyConfiguration.username = "andor";
        proxyConfiguration.password = "RogueOne";
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://andor:RogueOne@acme.proxy.io:80");
        assertEnv(envVars, HTTPS_PROXY_ENV, "http://andor:RogueOne@acme.proxy.io:80");
        assertEnv(envVars, JFROG_CLI_ENV_EXCLUDE, String.join(";", JFROG_CLI_DEFAULT_EXCLUSIONS, HTTP_PROXY_ENV, HTTPS_PROXY_ENV));
    }

    @Test
    void configureCliEnvHttpsProxyAuthTest() {
        proxyConfiguration.port = 443;
        proxyConfiguration.username = "andor";
        proxyConfiguration.password = "RogueOne";
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "https://andor:RogueOne@acme.proxy.io:443");
        assertEnv(envVars, HTTPS_PROXY_ENV, "https://andor:RogueOne@acme.proxy.io:443");
        assertEnv(envVars, JFROG_CLI_ENV_EXCLUDE, String.join(";", JFROG_CLI_DEFAULT_EXCLUSIONS, HTTP_PROXY_ENV, HTTPS_PROXY_ENV));
    }

    @Test
    void configureCliEnvNoOverrideHttpTest() {
        envVars.put(HTTP_PROXY_ENV, "http://acme2.proxy.io:777");
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://acme2.proxy.io:777");
    }

    @Test
    void configureCliEnvNoOverrideTest() {
        envVars.put(HTTP_PROXY_ENV, "http://acme2.proxy.io:80");
        envVars.put(HTTPS_PROXY_ENV, "http://acme2.proxy.io:443");
        invokeConfigureCliEnv();
        assertEnv(envVars, HTTP_PROXY_ENV, "http://acme2.proxy.io:80");
        assertEnv(envVars, HTTPS_PROXY_ENV, "http://acme2.proxy.io:443");
    }
}
