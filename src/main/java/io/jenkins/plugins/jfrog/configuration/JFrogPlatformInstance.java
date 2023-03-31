package io.jenkins.plugins.jfrog.configuration;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents an instance of jenkins JFrog instance configuration page.
 */
@Getter
@Setter
public class JFrogPlatformInstance implements Serializable {
    private static final String DISTRIBUTION_SUFFIX = "/distribution";
    private static final String ARTIFACTORY_SUFFIX = "/artifactory";
    private static final String XRAY_SUFFIX = "/xray";

    private String url;
    private String artifactoryUrl;
    private String distributionUrl;
    private String xrayUrl;
    private String id;
    private CredentialsConfig credentialsConfig;

    @DataBoundConstructor
    public JFrogPlatformInstance(String serverId, String url, CredentialsConfig credentialsConfig, String artifactoryUrl, String distributionUrl, String xrayUrl) {
        this.id = serverId;
        this.credentialsConfig = credentialsConfig;
        this.url = StringUtils.removeEnd(url, "/");
        this.artifactoryUrl = StringUtils.removeEnd(artifactoryUrl, "/");
        this.distributionUrl = StringUtils.removeEnd(distributionUrl, "/");
        this.xrayUrl = StringUtils.removeEnd(xrayUrl, "/");
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

    /**
     * Get Artifactory URL if configured. Otherwise, infer the Artifactory URL from the platform URL.
     *
     * @return Artifactory URL.
     */
    public String inferArtifactoryUrl() {
        return StringUtils.defaultIfBlank(artifactoryUrl, url + ARTIFACTORY_SUFFIX);
    }

    /**
     * Get Distribution URL if configured. Otherwise, infer the Distribution URL from the platform URL.
     *
     * @return Distribution URL.
     */
    public String inferDistributionUrl() {
        return StringUtils.defaultIfBlank(distributionUrl, this.url + DISTRIBUTION_SUFFIX);
    }

    /**
     * Get Xray URL if configured. Otherwise, infer the Xray URL from the platform URL.
     *
     * @return Distribution URL.
     */
    public String inferXrayUrl() {
        return StringUtils.defaultIfBlank(xrayUrl, this.url + XRAY_SUFFIX);
    }
}
