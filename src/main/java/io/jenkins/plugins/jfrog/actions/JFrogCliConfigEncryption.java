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
    private String keyOrPath;

    public JFrogCliConfigEncryption(EnvVars env) {
        if (env.containsKey(JFROG_CLI_HOME_DIR)) {
            // If JFROG_CLI_HOME_DIR exists, we assume that the user uses a permanent JFrog CLI configuration.
            // This type of configuration can not be encrypted because 2 different tasks may encrypt with 2 different keys.
            return;
        }
        this.shouldEncrypt = true;
        // UUID is a cryptographically strong encryption key. Without the dashes, it contains exactly 32 characters.
        String workspacePath = Paths.get("").toAbsolutePath().toString();

        Path encryptionDir = Paths.get(workspacePath, ".jfrog", "encryption");
        try {
            Files.createDirectories(encryptionDir);
            String fileName = UUID.randomUUID().toString() + ".key";
            Path keyFilePath = encryptionDir.resolve(fileName);
            String encryptionKeyContent = UUID.randomUUID().toString().replaceAll("-", "");
            Files.write(keyFilePath, encryptionKeyContent.getBytes(StandardCharsets.UTF_8));
            this.keyOrPath =keyFilePath.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getKey() {
        if (this.keyOrPath == null || this.keyOrPath.isEmpty()) {
            return null;
        }
        try {
            byte[] keyBytes = Files.readAllBytes(Paths.get(this.keyOrPath));
            return new String(keyBytes, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            System.err.println("Error reading encryption key file: " + e.getMessage());
            return null;
        }
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
