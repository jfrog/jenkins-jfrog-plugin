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
    private String keyContent;

    public JFrogCliConfigEncryption(EnvVars env) {
        if (env.containsKey(JFROG_CLI_HOME_DIR)) {
            // If JFROG_CLI_HOME_DIR exists, we assume that the user uses a permanent JFrog CLI configuration.
            // This type of configuration can not be encrypted because 2 different tasks may encrypt with 2 different keys.
            return;
        }
        this.shouldEncrypt = true;
        // UUID is a cryptographically strong encryption key. Without the dashes, it contains exactly 32 characters.
        String workspacePath = env.get("WORKSPACE");
        if (workspacePath == null || workspacePath.isEmpty()) {
            workspacePath = System.getProperty("java.io.tmpdir");
        }
        Path encryptionDir = Paths.get(workspacePath, ".jfrog", "encryption");
        try {
            Files.createDirectories(encryptionDir);
            String fileName = UUID.randomUUID().toString() + ".key";
            Path keyFilePath = encryptionDir.resolve(fileName);
            String encryptionKeyContent = UUID.randomUUID().toString().replaceAll("-", "");
            Files.write(keyFilePath, encryptionKeyContent.getBytes(StandardCharsets.UTF_8));
            this.keyOrPath = keyFilePath.toString();
            this.keyContent = encryptionKeyContent;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getKey() {
        if (this.keyContent != null && !this.keyContent.isEmpty()) {
            return this.keyContent;
        }
        if (this.keyOrPath == null || this.keyOrPath.isEmpty()) {
            throw new IllegalStateException("Encryption key is not initialized");
        }
        try {
            byte[] keyBytes = Files.readAllBytes(Paths.get(this.keyOrPath));
            String key = new String(keyBytes, StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                throw new IllegalStateException("Encryption key file is empty: " + this.keyOrPath);
            }
            this.keyContent = key;
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading encryption key file: " + this.keyOrPath, e);
        }
    }

    public String getKeyOrFilePath() {
        if (this.keyOrPath == null || this.keyOrPath.isEmpty()) {
            return null;
        }
        return this.keyOrPath;
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
