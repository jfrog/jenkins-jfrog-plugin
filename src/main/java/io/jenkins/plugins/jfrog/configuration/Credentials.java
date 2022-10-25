package io.jenkins.plugins.jfrog.configuration;

import hudson.util.Secret;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;


/**
 * Credentials model object
 */
public class Credentials implements Serializable {
    public static final Credentials EMPTY_CREDENTIALS = new Credentials(StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY);
    private Secret username;
    private Secret password;
    private Secret accessToken;

    /**
     * Main constructor
     *
     * @param username    Username
     * @param password    Clear-text password.
     * @param accessToken Clear-text accessToken.
     */
    public Credentials(String username, String password, String accessToken) {
        this.username = Secret.fromString(username);
        this.password = Secret.fromString(password);
        this.accessToken = Secret.fromString(accessToken);
    }

    public String getUsername() {
        return Secret.toString(username);
    }

    public String getPassword() {
        return Secret.toString(password);
    }

    public String getAccessToken() {
        return Secret.toString(accessToken);
    }

    @SuppressWarnings("unused")
    public void setAccessToken(Secret accessToken) {
        this.accessToken = accessToken;
    }

    @SuppressWarnings("unused")
    public void setUsername(Secret username) {
        this.username = username;
    }

    @SuppressWarnings("unused")
    public void setPassword(Secret password) {
        this.password = password;
    }
}
