package io.jenkins.plugins.jfrog.configuration;

import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class ProxyConfigurationTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    private final hudson.ProxyConfiguration jenkinsProxyConfiguration = new hudson.ProxyConfiguration("proxy.jfrog.io", 1234);
    private final String url;

    public ProxyConfigurationTest(String url) {
        this.url = url;
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Parameterized.Parameters
    public static Collection<Object[]> dataProvider() {
        return Arrays.asList(
                // HTTP
                new Object[]{"http://acme.jfrog.io"},
                new Object[]{"http://acme.jfrog.io/"},
                new Object[]{"http://acme.jfrog.io/artifactory"},
                new Object[]{"http://acme.jfrog.io:8081/artifactory"},

                // HTTPS
                new Object[]{"https://acme.jfrog.io"},
                new Object[]{"https://acme.jfrog.io/"},
                new Object[]{"https://acme.jfrog.io/artifactory"},
                new Object[]{"https://acme.jfrog.io:8081/artifactory"},

                // SSH
                new Object[]{"ssh://acme.jfrog.io"},
                new Object[]{"ssh://acme.jfrog.io/"},
                new Object[]{"ssh://acme.jfrog.io/artifactory"},
                new Object[]{"ssh://acme.jfrog.io:8081/artifactory"}
        );
    }

    @Test
    public void testShouldBypassProxy() {
        setupProxy("*");
        assertTrue(new ProxyConfiguration().shouldBypassProxy(url));

        setupProxy("acme.jfrog.*");
        assertTrue(new ProxyConfiguration().shouldBypassProxy(url));

        setupProxy("");
        assertFalse(new ProxyConfiguration().shouldBypassProxy(url));

        setupProxy("acme.jfrog.info");
        assertFalse(new ProxyConfiguration().shouldBypassProxy(url));
    }

    private void setupProxy(String noProxyHost) {
        Jenkins jenkins = jenkinsRule.getInstance();
        jenkinsProxyConfiguration.setNoProxyHost(noProxyHost);
        jenkins.setProxy(jenkinsProxyConfiguration);
    }
}
