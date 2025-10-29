package io.jenkins.plugins.jfrog.configuration;

import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.stream.Stream;

import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class JenkinsProxyConfigurationTest {

    private final hudson.ProxyConfiguration jenkinsProxyConfiguration = new hudson.ProxyConfiguration("proxy.jfrog.io", 1234);

    private JenkinsRule jenkinsRule;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        jenkinsRule = rule;
    }

    @SuppressWarnings("HttpUrlsUsage")
    static Stream<Arguments> dataProvider() {
        return Stream.of(
                // HTTP
                Arguments.of("http://acme.jfrog.io"),
                Arguments.of("http://acme.jfrog.io/"),
                Arguments.of("http://acme.jfrog.io/artifactory"),
                Arguments.of("http://acme.jfrog.io:8081/artifactory"),

                // HTTPS
                Arguments.of("https://acme.jfrog.io"),
                Arguments.of("https://acme.jfrog.io/"),
                Arguments.of("https://acme.jfrog.io/artifactory"),
                Arguments.of("https://acme.jfrog.io:8081/artifactory"),

                // SSH
                Arguments.of("ssh://acme.jfrog.io"),
                Arguments.of("ssh://acme.jfrog.io/"),
                Arguments.of("ssh://acme.jfrog.io/artifactory"),
                Arguments.of("ssh://acme.jfrog.io:8081/artifactory")
        );
    }

    @ParameterizedTest
    @MethodSource("dataProvider")
    void testShouldBypassProxy(String url) {
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
