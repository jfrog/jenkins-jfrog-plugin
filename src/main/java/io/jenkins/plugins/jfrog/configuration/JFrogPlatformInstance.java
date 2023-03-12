package io.jenkins.plugins.jfrog.configuration;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents an instance of jenkins JFrog instance configuration page.
 */
public class JFrogPlatformInstance implements Serializable {
    private String url;
    private String artifactoryUrl;
    private String distributionUrl;
    private String xrayUrl;
    private String id;
    private CredentialsConfig credentialsConfig;

    @DataBoundConstructor
    public JFrogPlatformInstance(String serverId, String url, CredentialsConfig credentialsConfig, String artifactoryUrl, String distributionUrl, String xrayUrl) {
        this.id = serverId;
        this.url = StringUtils.isNotEmpty(url) ? StringUtils.removeEnd(url, "/") : null;
        this.credentialsConfig = credentialsConfig;
        this.artifactoryUrl = addUrlSuffix(artifactoryUrl, this.url, "artifactory");
        this.distributionUrl = addUrlSuffix(distributionUrl, this.url, "distribution");
        this.xrayUrl = addUrlSuffix(xrayUrl, this.url, "xray");
    }

    public CredentialsConfig getCredentialsConfig() {
        return credentialsConfig;
    }

    /**
     * Returns the list of {@link JFrogPlatformInstance} configured.
     * Used by Jenkins Jelly for displaying values.
     *
     * @return can be empty but never null.
     */
    @SuppressWarnings("unused")
    public List<JFrogPlatformInstance> getJfrogInstances() {
        return JFrogPlatformBuilder.getJFrogPlatformInstances();
    }

    private String addUrlSuffix(String Url, String platformUrl, String suffix) {
        return StringUtils.isNotEmpty(Url) ? Url : platformUrl + "/" + suffix;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public String getId() {
        return id;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public String getUrl() {
        return url;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setUrl(String url) {
        this.url = url;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public String getServerId() {
        return getId();
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setServerId(String serverId) {
        this.id = serverId;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public String getArtifactoryUrl() {
        return artifactoryUrl;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setArtifactoryUrl(String artifactoryUrl) {
        this.artifactoryUrl = artifactoryUrl;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public String getDistributionUrl() {
        return distributionUrl;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setDistributionUrl(String distributionUrl) {
        this.distributionUrl = distributionUrl;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public String getXrayUrl() {
        return xrayUrl;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setXrayUrl(String xrayUrl) {
        this.xrayUrl = xrayUrl;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setId(String id) {
        this.id = id;
    }

    // Required by external plugins (JCasC).
    @SuppressWarnings("unused")
    public void setCredentialsConfig(CredentialsConfig credentialsConfig) {
        this.credentialsConfig = credentialsConfig;
    }
}
