package io.jenkins.plugins.jfrog.configuration;

import hudson.util.Secret;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;


/**
 * Credentials model object
 */
public class Credentials implements Serializable {
    public static final Secret EMPTY_SECRET = Secret.fromString(StringUtils.EMPTY);
    public static final Credentials EMPTY_CREDENTIALS = new Credentials(EMPTY_SECRET, EMPTY_SECRET, EMPTY_SECRET);
    private Secret username;
    private Secret password;
    private Secret accessToken;

    /**
     * Main constructor
     *
     * @param username    Secret username
     * @param password    Secret password.
     * @param accessToken Secret accessToken.
     */
    public Credentials(Secret username, Secret password, Secret accessToken) {
        this.username = username;
        this.password = password;
        this.accessToken = accessToken;
    }

    public Credentials() {
        this.username = EMPTY_SECRET;
        this.password = EMPTY_SECRET;
        this.accessToken = EMPTY_SECRET;
    }

    public Secret getUsername() {
        return username;
    }

    public Secret getPassword() {
        return password;
    }

    public Secret getAccessToken() {
        return accessToken;
    }

    public String getPlainTextUsername() {
        return Secret.toString(username);
    }

    public String getPlainTextPassword() {
        return Secret.toString(password);
    }

    public String getPlainTextAccessToken() {
        return Secret.toString(accessToken);
    }

    public void setAccessToken(Secret accessToken) {
        this.accessToken = accessToken;
    }

    public void setUsername(Secret username) {
        this.username = username;
    }

    public void setPassword(Secret password) {
        this.password = password;
    }
}
