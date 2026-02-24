package io.jenkins.plugins.jfrog.actions;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.JFROG_CLI_HOME_DIR;

/**
 * This action is injected to the JfStep in order to generate a random key that encrypts the JFrog CLI config.
 *
 * @author yahavi
 **/
public class JFrogCliConfigEncryption implements Action {
    private boolean shouldEncrypt;
    // The encryption key content (32 characters)
    private String key;
    // The path to the key file (set when writeKeyFile is called)
    private String keyFilePath;

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

    /**
     * Writes the encryption key to a file in the specified directory on the agent.
     * Uses FilePath to ensure the file is written on the remote agent, not the controller.
     * <p>
     * The key file is always written fresh to the given jfrogHomeTempDir. In multi-agent
     * pipelines each agent has its own filesystem, so the file must be written locally on
     * every agent where the JFrog CLI runs. The key content stays the same across agents.
     *
     * @param jfrogHomeTempDir - The JFrog CLI home temp directory (FilePath on the agent)
     * @return The path to the key file (as seen by the agent)
     * @throws IOException if the file cannot be written
     * @throws InterruptedException if the operation is interrupted
     */
    public String writeKeyFile(FilePath jfrogHomeTempDir) throws IOException, InterruptedException {
        if (this.key == null || this.key.isEmpty()) {
            return null;
        }
        // Always write the key file on the current agent's filesystem.
        // Do NOT cache/reuse keyFilePath: in multi-agent pipelines each agent has its own
        // filesystem, so returning a previously cached path would point to a different
        // agent's file which does not exist on the current agent.
        FilePath encryptionDir = jfrogHomeTempDir.child("encryption");
        encryptionDir.mkdirs();
        String fileName = UUID.randomUUID().toString() + ".key";
        FilePath keyFile = encryptionDir.child(fileName);
        keyFile.write(this.key, StandardCharsets.UTF_8.name());
        this.keyFilePath = keyFile.getRemote();
        return this.keyFilePath;
    }

    public String getKey() {
        return this.key;
    }

    public String getKeyFilePath() {
        return this.keyFilePath;
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
