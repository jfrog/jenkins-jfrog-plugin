package io.jenkins.plugins.jfrog.actions;

import hudson.EnvVars;
import hudson.model.Action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * Writes the encryption key to a file in the specified directory.
     * This should be called with the jfrogHomeTempDir path which is accessible inside Docker containers.
     *
     * @param jfrogHomeTempDir - The JFrog CLI home temp directory path (accessible from agent/Docker)
     * @return The path to the key file
     * @throws IOException if the file cannot be written
     */
    public String writeKeyFile(String jfrogHomeTempDir) throws IOException {
        if (this.key == null || this.key.isEmpty()) {
            return null;
        }
        // If key file was already written, return the existing path
        if (this.keyFilePath != null) {
            return this.keyFilePath;
        }
        Path encryptionDir = Paths.get(jfrogHomeTempDir, "encryption");
        Files.createDirectories(encryptionDir);
        String fileName = UUID.randomUUID().toString() + ".key";
        Path keyPath = encryptionDir.resolve(fileName);
        Files.write(keyPath, this.key.getBytes(StandardCharsets.UTF_8));
        this.keyFilePath = keyPath.toString();
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
