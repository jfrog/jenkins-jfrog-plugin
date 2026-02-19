package io.jenkins.plugins.jfrog;

import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.callables.JFrogCliDownloader;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.jfrog.build.client.DownloadResponse.SHA256_HEADER_NAME;

/**
 * Installer for JFrog CLI binary.
 *
 * @author gail
 */
public abstract class BinaryInstaller extends ToolInstaller {
    
    private static final Logger LOGGER = Logger.getLogger(BinaryInstaller.class.getName());
    
    /**
     * Per-node synchronization locks for installation coordination.
     * Key: Node name + tool installation path
     * Value: Object used as synchronization lock
     */
    private static final ConcurrentHashMap<String, Object> NODE_INSTALLATION_LOCKS = new ConcurrentHashMap<>();
    
    protected BinaryInstaller(String label) {
        super(label);
    }

    /**
     * @param tool the tool being installed.
     * @param node the computer on which to install the tool.
     * @return Node's filesystem location where a tool should be installed.
     */
    protected FilePath getToolLocation(ToolInstallation tool, Node node) throws IOException, InterruptedException {
        FilePath location = preferredLocation(tool, node);
        if (!location.exists()) {
            location.mkdirs();
        }
        return location;
    }

    public abstract static class DescriptorImpl<T extends BinaryInstaller> extends ToolInstallerDescriptor<T> {
        /**
         * This ID needs to be unique, and needs to match the ID token in the JSON update file.
         * <p>
         * By default, we use the fully-qualified class name of the {@link BinaryInstaller} subtype.
         */
        @Override
        public String getId() {
            return clazz.getName().replace('$', '.');
        }
    }

    /**
     * Performs JFrog CLI installation with proper synchronization to ensure reliable installation.
     * 
     * INSTALLATION STRATEGY:
     * 1. Create unique lock key per node + installation path
     * 2. Use synchronized block to ensure only one installation per location at a time
     * 3. Check if CLI already exists and is valid before downloading
     * 4. Download using atomic file operations for reliability
     * 
     * @param toolLocation Target directory for CLI installation
     * @param log Task listener for logging progress
     * @param version CLI version to install
     * @param instance JFrog platform instance for download
     * @param repository Repository containing the CLI binary
     * @param binaryName Name of the CLI binary file
     * @return FilePath of the installed CLI
     * @throws IOException If installation fails
     * @throws InterruptedException If installation is interrupted
     */
    public static FilePath performJfrogCliInstallation(FilePath toolLocation, TaskListener log, String version, 
                                                      JFrogPlatformInstance instance, String repository, String binaryName) 
            throws IOException, InterruptedException {
        
        // Create unique lock key for this node + installation path combination.
        // Version is intentionally excluded to serialize all writes to the same binary path.
        String lockKey = createLockKey(toolLocation, binaryName, version);
        
        // Get or create synchronization lock for this specific installation location
        Object installationLock = NODE_INSTALLATION_LOCKS.computeIfAbsent(lockKey, k -> new Object());
        
        log.getLogger().println("[BinaryInstaller] Acquiring installation lock for: " + lockKey);
        
        // Synchronize on the specific installation location to ensure coordinated installation
        synchronized (installationLock) {
            log.getLogger().println("[BinaryInstaller] Lock acquired, proceeding with installation");
            
            try {
                // Check if CLI already exists and is the correct version
                FilePath cliPath = toolLocation.child(binaryName);
                boolean validCliExists = isValidCliInstallation(cliPath, log);
                if (validCliExists && isCorrectVersion(toolLocation, instance, repository, version, binaryName, log)) {
                    log.getLogger().println("[BinaryInstaller] CLI already installed and up-to-date, skipping download");
                    return toolLocation;
                } else if (validCliExists) {
                    log.getLogger().println("[BinaryInstaller] CLI exists but version mismatch detected, proceeding with upgrade");
                } else {
                    log.getLogger().println("[BinaryInstaller] No valid CLI installation found, proceeding with fresh installation");
                }
                
                // Clean up any stale lock entries for this location (different versions)
                cleanupStaleLocks(toolLocation, binaryName, version);
                
                log.getLogger().println("[BinaryInstaller] Starting CLI installation process");
                
                // Perform the actual download using the improved JFrogCliDownloader
                JenkinsProxyConfiguration proxyConfiguration = new JenkinsProxyConfiguration();
                toolLocation.act(new JFrogCliDownloader(proxyConfiguration, version, instance, log, repository, binaryName));
                
                log.getLogger().println("[BinaryInstaller] CLI installation completed successfully");
                return toolLocation;
                
            } finally {
                log.getLogger().println("[BinaryInstaller] Installation lock released for: " + lockKey);
            }
        }
    }
    
    /**
     * Creates a unique lock key for the installation location.
     * Version is excluded so all operations targeting the same binary path are serialized.
     *
     * @param toolLocation Installation directory
     * @param binaryName Binary file name
     * @param version CLI version being installed (unused, kept for signature compatibility)
     * @return Unique lock key string
     */
    private static String createLockKey(FilePath toolLocation, String binaryName, String version) {
        try {
            return toolLocation.getRemote() + "/" + binaryName;
        } catch (Exception e) {
            // Fallback to a simpler key if remote path access fails
            return "unknown-tool-location/" + binaryName;
        }
    }
    
    /**
     * Checks if a valid CLI installation already exists at the specified location.
     * 
     * @param cliPath Path to the CLI binary
     * @param log Task listener for logging
     * @return true if valid CLI exists, false otherwise
     */
    private static boolean isValidCliInstallation(FilePath cliPath, TaskListener log) {
        try {
            if (cliPath.exists()) {
                // Check if file is executable and has reasonable size (> 1MB)
                long fileSize = cliPath.length();
                if (fileSize > 1024 * 1024 && isExecutable(cliPath)) { // > 1MB
                    log.getLogger().println("[BinaryInstaller] Found existing CLI: " + cliPath.getRemote() + 
                                          " (size: " + (fileSize / 1024 / 1024) + "MB)");
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to check existing CLI installation: " + e.getMessage());
        }
        return false;
    }

    /**
     * Verify the CLI file is executable on the target node.
     * On Windows, treat .exe files as executable.
     */
    private static boolean isExecutable(FilePath cliPath) throws IOException, InterruptedException {
        return cliPath.act(new MasterToSlaveFileCallable<Boolean>() {
            @Override
            public Boolean invoke(File file, VirtualChannel channel) {
                if (!file.exists() || file.isDirectory()) {
                    return false;
                }
                String name = file.getName().toLowerCase();
                if (name.endsWith(".exe")) {
                    return true;
                }
                return file.canExecute();
            }
        });
    }
    
    /**
     * Check if the installed CLI is the correct version by comparing SHA256 hashes.
     * This prevents unnecessary downloads when the CLI is already up-to-date.
     * 
     * @param toolLocation Directory containing the CLI
     * @param instance JFrog platform instance for version checking
     * @param repository Repository containing the CLI
     * @param version Version to check
     * @param binaryName Name of the CLI binary (e.g., "jf" on Unix, "jf.exe" on Windows)
     * @param log Task listener for logging
     * @return true if CLI is the correct version, false otherwise
     */
    private static boolean isCorrectVersion(FilePath toolLocation, JFrogPlatformInstance instance, 
                                          String repository, String version, String binaryName, TaskListener log) {
        try {
            // Use the same logic as shouldDownloadTool() from JFrogCliDownloader
            // but do it here to avoid unnecessary JFrogCliDownloader.invoke() calls
            
            JenkinsProxyConfiguration proxyConfiguration = new JenkinsProxyConfiguration();
            // Use binaryName to construct the correct URL suffix (handles Windows jf.exe vs Unix jf)
            String cliUrlSuffix = String.format("/%s/v2-jf/%s/jfrog-cli-%s/%s", repository, 
                                               StringUtils.defaultIfBlank(version, "[RELEASE]"), 
                                               OsUtils.getOsDetails(), binaryName);
            
            JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(log);
            String artifactoryUrl = instance.inferArtifactoryUrl();
            
            try (ArtifactoryManager manager = new ArtifactoryManager(artifactoryUrl, 
                    Secret.toString(instance.getCredentialsConfig().getUsername()),
                    Secret.toString(instance.getCredentialsConfig().getPassword()), 
                    Secret.toString(instance.getCredentialsConfig().getAccessToken()), buildInfoLog)) {
                
                if (proxyConfiguration.isProxyConfigured(artifactoryUrl)) {
                    manager.setProxyConfiguration(proxyConfiguration);
                }
                
                // Get expected SHA256 from Artifactory
                String expectedSha256 = getArtifactSha256(manager, cliUrlSuffix);
                if (expectedSha256.isEmpty()) {
                    log.getLogger().println("[BinaryInstaller] No SHA256 available from server, reusing existing valid CLI");
                    return true;
                }
                
                // Check local SHA256 file
                return toolLocation.act(new MasterToSlaveFileCallable<Boolean>() {
                    @Override
                    public Boolean invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                        File sha256File = new File(f, "sha256");
                        if (!sha256File.exists()) {
                            return false;
                        }
                        
                        String localSha256 = new String(Files.readAllBytes(sha256File.toPath()), StandardCharsets.UTF_8);
                        return constantTimeEquals(expectedSha256, localSha256);
                    }
                });
            }
            
        } catch (Exception e) {
            log.getLogger().println("[BinaryInstaller] Version check failed: " + e.getMessage() + ", proceeding with download check");
            return false; // If version check fails, let download process handle it
        }
    }

    /**
     * Get SHA256 hash from Artifactory headers (same logic as in JFrogCliDownloader)
     */
    private static String getArtifactSha256(ArtifactoryManager manager, String cliUrlSuffix) throws IOException {
        Header[] headers = manager.downloadHeaders(cliUrlSuffix);
        for (Header header : headers) {
            String headerName = header.getName();
            if (headerName.equalsIgnoreCase(SHA256_HEADER_NAME) ||
                    headerName.equalsIgnoreCase("X-Artifactory-Checksum-Sha256")) {
                return header.getValue();
            }
        }
        return "";
    }
    
    /**
     * Clean up stale lock entries for different versions of the same CLI at the same location.
     * This prevents memory leaks in the NODE_INSTALLATION_LOCKS map during version upgrades.
     * 
     * @param toolLocation Installation directory
     * @param binaryName Binary file name  
     * @param currentVersion Current version being installed
     */
    private static void cleanupStaleLocks(FilePath toolLocation, String binaryName, String currentVersion) {
        try {
            String locationPrefix = toolLocation.getRemote() + "/" + binaryName + "/";
            String currentLockKey = locationPrefix + currentVersion;
            
            // Remove old version lock entries for the same location
            NODE_INSTALLATION_LOCKS.entrySet().removeIf(entry -> {
                String key = entry.getKey();
                return key.startsWith(locationPrefix) && !key.equals(currentLockKey);
            });
            
        } catch (Exception e) {
            // If cleanup fails, it's not critical - just log and continue
            LOGGER.fine("Failed to cleanup stale locks: " + e.getMessage());
        }
    }
    
    /**
     * Constant-time comparison of two strings to prevent timing attacks.
     * This is especially important for comparing cryptographic hashes like SHA256.
     * 
     * @param a First string to compare
     * @param b Second string to compare
     * @return true if strings are equal, false otherwise
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        
        if (a.length() != b.length()) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        
        return result == 0;
    }
}

