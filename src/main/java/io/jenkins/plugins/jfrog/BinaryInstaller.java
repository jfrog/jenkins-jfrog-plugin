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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
     * Environment variable that overrides the default lock-acquisition timeout (in minutes).
     * Set this on the Jenkins controller when operating on slow networks where CLI downloads
     * take longer than the default.
     *
     * <pre>
     *   export JFROG_CLI_INSTALL_TIMEOUT_MINUTES=15
     * </pre>
     */
    static final String INSTALL_TIMEOUT_ENV_VAR = "JFROG_CLI_INSTALL_TIMEOUT_MINUTES";
    private static final int DEFAULT_INSTALL_TIMEOUT_MINUTES = 5;

    /**
     * Per-node synchronization locks for installation coordination.
     * Key: installation path + binary name (see {@link #createLockKey})
     * Value: ReentrantLock used to serialize installations to the same path
     *
     * <p>The map grows by one entry per unique (agent path, binary) combination encountered
     * during the lifetime of the Jenkins JVM. In typical deployments the number of distinct
     * tool installation paths is small and bounded, so the unbounded growth is acceptable.
     * If the entry count ever becomes a concern, entries can be evicted after a successful
     * installation without loss of correctness (a new lock will be created on the
     * next access).</p>
     */
    private static final ConcurrentHashMap<String, ReentrantLock> NODE_INSTALLATION_LOCKS = new ConcurrentHashMap<>();

    /**
     * Per-pipeline installation verification cache.
     * Key: tool path + agent OS + binary name
     * Value: pipeline run identifier (derived from the build's log storage)
     *
     * <p>Once a tool is verified in a pipeline run, all subsequent stages in the same run
     * skip the version check entirely — avoiding repeated HTTP HEAD requests to Artifactory.
     * A new pipeline run produces a different run ID, so it gets its own verification.
     * The agent OS is included in the key to distinguish same-path installations on
     * agents with different architectures (e.g., linux-amd64 vs linux-arm64).</p>
     */
    private static final ConcurrentHashMap<String, String> VERIFIED_IN_RUN = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 100;

    /**
     * Cache of agent OS details to avoid repeated remote calls.
     * Key: tool location remote path
     * Value: OS details string (e.g., "linux-amd64", "mac-arm64")
     */
    private static final ConcurrentHashMap<String, String> AGENT_OS_CACHE = new ConcurrentHashMap<>();

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
     * Performs JFrog CLI installation ensuring the pipeline always has a working binary.
     *
     * INSTALLATION STRATEGY:
     * 1. Fast path (no lock): binary exists and sha256 check passes — return immediately.
     *    When the server does not return a sha256 header, the check treats "no hash" as
     *    "up-to-date", so a missing or empty sha256 file never causes a re-download loop.
     * 2. Slow path (lock + download): acquire a ReentrantLock (configurable timeout, default
     *    5 min) and call the downloader.  Re-check version inside the lock in case a
     *    concurrent stage just finished.
     * 3. Fallbacks: if the lock times out or the download fails, use any existing valid binary
     *    rather than failing the pipeline.  Only throw when there is truly nothing to run.
     *
     * @param toolLocation Target directory for CLI installation
     * @param log Task listener for logging progress
     * @param version CLI version to install (blank = latest)
     * @param instance JFrog platform instance for download
     * @param repository Repository containing the CLI binary
     * @param binaryName Name of the CLI binary file
     * @return FilePath of the installed CLI
     * @throws IOException If installation fails and no existing binary is available
     * @throws InterruptedException If installation is interrupted
     */
    public static FilePath performJfrogCliInstallation(FilePath toolLocation, TaskListener log, String version,
                                                       JFrogPlatformInstance instance, String repository,
                                                       String binaryName, String nodeName)
            throws IOException, InterruptedException {

        FilePath cliPath = toolLocation.child(binaryName);

        // Cache agent OS per node+path to handle agents with identical tool paths but different architectures.
        String toolPath = toolLocation.getRemote();
        String osCacheKey = nodeName + ":" + toolPath;
        evictIfFull(AGENT_OS_CACHE);
        String agentOs = AGENT_OS_CACHE.computeIfAbsent(osCacheKey, k -> {
            try {
                return toolLocation.act(new MasterToSlaveFileCallable<String>() {
                    @Override
                    public String invoke(File f, VirtualChannel channel) throws IOException {
                        return OsUtils.getOsDetails();
                    }
                });
            } catch (Exception e) {
                LOGGER.warning("Failed to get agent OS details: " + e.getMessage());
                return "unknown";
            }
        });

        String cacheKey = nodeName + ":" + toolPath + "/" + agentOs + "/" + binaryName;
        LOGGER.fine("Agent OS detected: " + agentOs + " for node: " + nodeName + " tool: " + toolPath);

        // Per-pipeline cache: skip re-verification if already checked in this pipeline run.
        String currentRunId = getCurrentRunId(log);
        if (currentRunId != null && currentRunId.equals(VERIFIED_IN_RUN.get(cacheKey))) {
            LOGGER.fine("CLI already verified in this pipeline run, skipping check for: " + cacheKey);
            return toolLocation;
        }

        // Fast path: binary exists and is already the correct version — skip lock entirely.
        if (isValidCliInstallation(cliPath, log) && isCorrectVersion(toolLocation, instance, repository, version, binaryName, agentOs, log)) {
            log.getLogger().println("[BinaryInstaller] CLI already installed and up-to-date, skipping download");
            markVerified(cacheKey, currentRunId);
            return toolLocation;
        }

        // Slow path: need to install or upgrade.
        String lockKey = createLockKey(toolLocation, binaryName);
        ReentrantLock installationLock = NODE_INSTALLATION_LOCKS.computeIfAbsent(lockKey, k -> new ReentrantLock());

        int timeoutMinutes = getInstallTimeoutMinutes();
        log.getLogger().println("[BinaryInstaller] Acquiring installation lock for: " + lockKey + " (timeout: " + timeoutMinutes + " min)");

        if (!installationLock.tryLock(timeoutMinutes, TimeUnit.MINUTES)) {
            log.getLogger().println("[BinaryInstaller] WARNING: Could not acquire installation lock within " + timeoutMinutes + " minutes for: " + lockKey);
            if (isValidCliInstallation(cliPath, log)) {
                log.getLogger().println("[BinaryInstaller] Using existing binary while installation is in progress: " + cliPath.getRemote());
                return toolLocation;
            }
            throw new IOException("Timed out after " + timeoutMinutes + " minutes waiting for JFrog CLI installation and no binary exists at: " + cliPath.getRemote() +
                    ". Set " + INSTALL_TIMEOUT_ENV_VAR + " to increase the timeout.");
        }

        log.getLogger().println("[BinaryInstaller] Lock acquired, proceeding with installation");
        try {
            // Re-check inside the lock — a concurrent stage may have just finished.
            boolean validCliExists = isValidCliInstallation(cliPath, log);
            if (validCliExists && isCorrectVersion(toolLocation, instance, repository, version, binaryName, agentOs, log)) {
                log.getLogger().println("[BinaryInstaller] CLI was installed by a concurrent stage, skipping download");
                markVerified(cacheKey, currentRunId);
                return toolLocation;
            }

            if (validCliExists) {
                log.getLogger().println("[BinaryInstaller] CLI version mismatch detected, upgrading");
            } else {
                log.getLogger().println("[BinaryInstaller] No valid CLI installation found, downloading");
            }

            try {
                JenkinsProxyConfiguration proxyConfiguration = new JenkinsProxyConfiguration();
                toolLocation.act(new JFrogCliDownloader(proxyConfiguration, version, instance, log, repository, binaryName));
                log.getLogger().println("[BinaryInstaller] CLI installation completed successfully");
                markVerified(cacheKey, currentRunId);
            } catch (Exception e) {
                // Download failed. If an older binary is still present, keep the pipeline running.
                // The upgrade will be retried on the next run.
                if (isValidCliInstallation(cliPath, log)) {
                    log.getLogger().println("[BinaryInstaller] WARNING: Download failed (" + e.getMessage() +
                            "), falling back to existing binary at: " + cliPath.getRemote());
                    return toolLocation;
                }
                // No binary to fall back to — this is a genuine unrecoverable failure.
                throw new IOException("JFrog CLI download failed and no existing binary is available: " + e.getMessage(), e);
            }

            return toolLocation;

        } finally {
            installationLock.unlock();
            log.getLogger().println("[BinaryInstaller] Installation lock released for: " + lockKey);
        }
    }
    
    /**
     * Returns the lock-acquisition timeout in minutes.
     * Reads {@value #INSTALL_TIMEOUT_ENV_VAR} from the environment; falls back to
     * {@value #DEFAULT_INSTALL_TIMEOUT_MINUTES} minutes if the variable is absent or invalid.
     * Values below 1 are silently clamped to the default.
     */
    static int getInstallTimeoutMinutes() {
        String envValue = System.getenv(INSTALL_TIMEOUT_ENV_VAR);
        if (StringUtils.isNotBlank(envValue)) {
            try {
                int parsed = Integer.parseInt(envValue.trim());
                if (parsed >= 1) {
                    return parsed;
                }
                LOGGER.warning(INSTALL_TIMEOUT_ENV_VAR + "=" + envValue + " is less than 1, using default " + DEFAULT_INSTALL_TIMEOUT_MINUTES + " minutes");
            } catch (NumberFormatException e) {
                LOGGER.warning(INSTALL_TIMEOUT_ENV_VAR + "=" + envValue + " is not a valid integer, using default " + DEFAULT_INSTALL_TIMEOUT_MINUTES + " minutes");
            }
        }
        return DEFAULT_INSTALL_TIMEOUT_MINUTES;
    }

    /**
     * Extracts a unique pipeline run identifier from the TaskListener.
     * <p>
     * In Pipeline builds, the TaskListener wraps a FileLogStorage whose log file path
     * contains the job name and build number (e.g., "jobs/my-job/builds/42/log").
     * This path is guaranteed unique per pipeline run.
     * <p>
     * Falls back to null for non-Pipeline builds (Freestyle, etc.) where the listener
     * structure is different — the caller treats null as "don't cache".
     * <p>
     * <b>Fragile:</b> This relies on internal field names in workflow-api and workflow-support.
     * Tested with Jenkins {@literal >=} 2.462.3 and workflow-cps. If a future Jenkins update
     * renames these fields, the catch block returns null and caching degrades gracefully
     * (repeated version checks, no failure). Re-verify after major Jenkins core upgrades.
     */
    private static String getCurrentRunId(TaskListener log) {
        try {
            // Reflection chain (workflow-api / workflow-support internals):
            // CloseableTaskListener → mainDelegate (BufferedBuildListener) → out (IndexOutputStream) → this$0 (FileLogStorage) → log (File)
            java.lang.reflect.Field mainField = log.getClass().getDeclaredField("mainDelegate");
            mainField.setAccessible(true);
            Object buildListener = mainField.get(log);

            java.lang.reflect.Field outField = buildListener.getClass().getDeclaredField("out");
            outField.setAccessible(true);
            Object indexOut = outField.get(buildListener);

            java.lang.reflect.Field storageField = indexOut.getClass().getDeclaredField("this$0");
            storageField.setAccessible(true);
            Object fileLogStorage = storageField.get(indexOut);

            java.lang.reflect.Field logField = fileLogStorage.getClass().getDeclaredField("log");
            logField.setAccessible(true);
            File logFile = (File) logField.get(fileLogStorage);

            return logFile.getPath();
        } catch (Exception e) {
            // Non-Pipeline build or different Jenkins version — fall back gracefully
            LOGGER.fine("Could not determine pipeline run ID: " + e.getMessage());
            return null;
        }
    }

    private static void markVerified(String cacheKey, String currentRunId) {
        if (currentRunId == null) {
            return;
        }
        evictIfFull(VERIFIED_IN_RUN);
        VERIFIED_IN_RUN.put(cacheKey, currentRunId);
    }

    /**
     * Clears the cache if it has reached {@link #MAX_CACHE_SIZE}.
     * This is a simple eviction strategy — the slight race between size() and clear()
     * is harmless (worst case: one extra entry before eviction, or an extra cache miss).
     */
    private static void evictIfFull(ConcurrentHashMap<String, String> cache) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.clear();
        }
    }

    /**
     * Creates a unique lock key for the installation location.
     * Version is excluded so all operations targeting the same binary path are serialized.
     */
    private static String createLockKey(FilePath toolLocation, String binaryName) {
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
    /**
     * Checks existence, size (> 1 MB), and executable permission in a single agent RPC.
     */
    private static boolean isValidCliInstallation(FilePath cliPath, TaskListener log) {
        try {
            long[] result = cliPath.act(new MasterToSlaveFileCallable<long[]>() {
                @Override
                public long[] invoke(File file, VirtualChannel channel) {
                    if (!file.exists() || file.isDirectory()) {
                        return new long[]{0, 0};
                    }
                    String name = file.getName().toLowerCase();
                    boolean executable = name.endsWith(".exe") || file.canExecute();
                    return new long[]{file.length(), executable ? 1 : 0};
                }
            });
            if (result[0] > 1024 * 1024 && result[1] == 1) {
                log.getLogger().println("[BinaryInstaller] Found existing CLI: " + cliPath.getRemote() +
                        " (size: " + (result[0] / 1024 / 1024) + "MB)");
                return true;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to check existing CLI installation: " + e.getMessage());
        }
        return false;
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
                                          String repository, String version, String binaryName,
                                          String agentOsDetails, TaskListener log) {
        try {
            JenkinsProxyConfiguration proxyConfiguration = new JenkinsProxyConfiguration();
            String cliUrlSuffix = String.format("/%s/v2-jf/%s/jfrog-cli-%s/%s", repository,
                                               StringUtils.defaultIfBlank(version, "[RELEASE]"),
                                               agentOsDetails, binaryName);
            
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
                    log.getLogger().println("[BinaryInstaller] WARNING: No SHA256 available from server — cannot verify version, assuming up-to-date (upgrade may be delayed)");
                    // Clean up stale 0-byte sha256 file left by older plugin versions
                    cleanupStaleSha256File(toolLocation, log);
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
                        return StringUtils.equals(expectedSha256, localSha256);
                    }
                });
            }
            
        } catch (Exception e) {
            log.getLogger().println("[BinaryInstaller] Version check failed: " + e.getMessage() + ", proceeding with download check");
            return false; // If version check fails, let download process handle it
        }
    }

    /**
     * Removes a stale 0-byte sha256 file left behind by older plugin versions.
     * The file has no effect on current behaviour but can confuse operators inspecting
     * the tool directory.
     */
    private static void cleanupStaleSha256File(FilePath toolLocation, TaskListener log) {
        try {
            toolLocation.act(new MasterToSlaveFileCallable<Void>() {
                @Override
                public Void invoke(File f, VirtualChannel channel) throws IOException {
                    File sha256File = new File(f, "sha256");
                    if (sha256File.exists() && sha256File.length() == 0) {
                        if (sha256File.delete()) {
                            log.getLogger().println("[BinaryInstaller] Cleaned up stale 0-byte sha256 file");
                        }
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            // Best-effort cleanup — not worth failing the build
            LOGGER.warning("Failed to clean up stale sha256 file: " + e.getMessage());
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
    
}


