package io.jenkins.plugins.jfrog.configuration;

import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.build.client.ProxyConfiguration;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the proxy configuration that is retrieved from the Jenkins settings.
 * This information is populated from the Plugin Manager's Advanced section.
 * Because of the Jenkins.get() used in the constructor, you can only create it on the master, not on an agent.
 */
public class JenkinsProxyConfiguration extends ProxyConfiguration {
    private static final Pattern HOST_NAME_PATTERN = Pattern.compile("^.*?://?([\\w.-]+).*");
    public List<Pattern> noProxyHostPatterns;
    public String noProxy;

    public JenkinsProxyConfiguration() {
        hudson.ProxyConfiguration proxy = Jenkins.get().getProxy();
        if (proxy == null) {
            return;
        }
        this.host = proxy.getName();
        this.port = proxy.getPort();
        this.username = proxy.getUserName();
        this.password = Secret.toString(proxy.getSecretPassword());
        this.noProxy = proxy.getNoProxyHost();
        this.noProxyHostPatterns = proxy.getNoProxyHostPatterns();
    }

    /**
     * Return true if the proxy is configured.
     *
     * @return true if the proxy is configured.
     */
    public boolean isProxyConfigured() {
        return StringUtils.isNotBlank(host);
    }

    /**
     * Return true if the proxy is configured and not bypassed.
     *
     * @return true if the proxy is configured.
     */
    public boolean isProxyConfigured(String url) {
        return isProxyConfigured() && !shouldBypassProxy(url);
    }

    /**
     * Return true if the host matches one of the 'No Proxy Host' patterns.
     *
     * @param url - Server URL to check
     * @return true if should bypass proxy.
     */
    public boolean shouldBypassProxy(String url) {
        Matcher matcher = HOST_NAME_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return false;
        }
        String host = matcher.group(1);
        for (Pattern noProxyHostPattern : noProxyHostPatterns) {
            if (noProxyHostPattern.matcher(host).matches()) {
                return true;
            }
        }
        return false;
    }
}
