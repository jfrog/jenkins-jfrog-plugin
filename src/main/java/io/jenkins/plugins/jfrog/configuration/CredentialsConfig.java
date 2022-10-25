package io.jenkins.plugins.jfrog.configuration;

import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;

/**
 * Configuration for all available credentials providers.
 */
public class CredentialsConfig implements Serializable {
    private final Credentials credentials;
    private final String credentialsId;

    /**
     * This object obtains the username, password, accessToken and credentials id (used with the Credentials plugin)
     * Each of these properties could be empty string if not specified but not null
     */
    @SuppressWarnings("unused")
    @DataBoundConstructor
    public CredentialsConfig(String username, String password, String accessToken, String credentialsId) {
        this.credentials = new Credentials(username, password, accessToken);
        this.credentialsId = credentialsId;
    }

    public CredentialsConfig(String credentialsId, Credentials credentials) {
        this.credentialsId = credentialsId;
        this.credentials = credentials;
    }

    public String getUsername() {
        return this.credentials.getUsername();
    }

    public String getPassword() {
        return this.credentials.getPassword();
    }

    public String getAccessToken() {
        return this.credentials.getAccessToken();
    }

    @SuppressWarnings("unused")
    public String getCredentialsId() {
        return credentialsId;
    }
}
