package io.jenkins.plugins.jfrog.configuration;

import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;

/**
 * Configuration for all available credentials providers.
 */
public class CredentialsConfig implements Serializable {
    private final Credentials credentials;
    private final String credentialsId;

    /**
     * This object obtains the username, password and credentials id (used with the Credentials plugin)
     * Each of these properties could be empty string if not specified but not null
     */
    @DataBoundConstructor
    public CredentialsConfig(Secret username, Secret password, Secret accessToken, String credentialsId) {
        this.credentials = new Credentials(username, password, accessToken);
        this.credentialsId = credentialsId;
    }

    public Secret getUsername() {
        return this.credentials.getUsername();
    }

    public Secret getPassword() {
        return this.credentials.getPassword();
    }

    public Secret getAccessToken() {
        return this.credentials.getAccessToken();
    }

    public String getCredentialsId() {
        return credentialsId;
    }
}
