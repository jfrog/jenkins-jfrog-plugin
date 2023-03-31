package io.jenkins.plugins.jfrog.actions;

import hudson.EnvVars;
import hudson.model.Action;

import java.util.UUID;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.JFROG_CLI_HOME_DIR;

/**
 * This action is injected to the JfStep in order to generate a random key that encrypts the JFrog CLI config.
 *
 * @author yahavi
 **/
public class JFrogCliConfigEncryption implements Action {
    private boolean shouldEncrypt;
    private String key;

    public JFrogCliConfigEncryption(EnvVars env) {
        if (env.containsKey(JFROG_CLI_HOME_DIR)) {
            // If JFROG_CLI_HOME_DIR exists, we assume that the user uses a permanent JFrog CLI configuration.
            // This type of configuration can not be encrypted because 2 different tasks may encrypt with 2 different keys.
            return;
        }
        this.shouldEncrypt = true;
        // UUID is a cryptographically strong encryption key. Without the dashes, it contains exactly 32 characters.
        this.key = UUID.randomUUID().toString().replaceAll("-", "");
    }

    public String getKey() {
        return key;
    }

    public boolean shouldEncrypt() {
        return shouldEncrypt;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "JFrog CLI config encryption";
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
