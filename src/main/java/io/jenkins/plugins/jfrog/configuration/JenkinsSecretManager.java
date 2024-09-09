package io.jenkins.plugins.jfrog.configuration;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;


import java.io.IOException;
import java.util.List;


public class JenkinsSecretManager {

    public void createSecret(String name, String value, String description) {
        StringCredentialsImpl secret = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                name,
                description,
                Secret.fromString(value)
        );
        try {
            SystemCredentialsProvider.getInstance().getCredentials().add(secret);
            SystemCredentialsProvider.getInstance().save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteSecret(String id) {
        List<Credentials> credentials = SystemCredentialsProvider.getInstance().getCredentials();
        credentials.removeIf(cred -> {
            cred.getScope();
            return false;
        });
        try {
            SystemCredentialsProvider.getInstance().save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
