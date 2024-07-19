package io.jenkins.plugins.jfrog.configuration;

import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
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
public class JenkinsProxyConfigurationTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    private final hudson.ProxyConfiguration jenkinsProxyConfiguration = new hudson.ProxyConfiguration("proxy.jfrog.io", 1234);
    private final String url;

    public JenkinsProxyConfigurationTest(String url) {
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
        assertTrue(new JenkinsProxyConfiguration().shouldBypassProxy(url));

        setupProxy("acme.jfrog.*");
        assertTrue(new JenkinsProxyConfiguration().shouldBypassProxy(url));

        setupProxy("");
        assertFalse(new JenkinsProxyConfiguration().shouldBypassProxy(url));

        setupProxy("acme.jfrog.info");
        assertFalse(new JenkinsProxyConfiguration().shouldBypassProxy(url));

        setupProxy("acme.jfrog.io-dashed");
        String dashedUrl = StringUtils.replace(url, "acme.jfrog.io", "acme.jfrog.io-dashed");
        assertTrue(new JenkinsProxyConfiguration().shouldBypassProxy(dashedUrl));
    }

    private void setupProxy(String noProxyHost) {
        Jenkins jenkins = jenkinsRule.getInstance();
        jenkinsProxyConfiguration.setNoProxyHost(noProxyHost);
        jenkins.setProxy(jenkinsProxyConfiguration);
    }
}
