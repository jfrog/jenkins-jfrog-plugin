package io.jenkins.plugins.jfrog.callables;

import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.JenkinsBuildInfoLog;
import io.jenkins.plugins.jfrog.OsUtils;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import jenkins.MasterToSlaveFileCallable;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.jfrog.build.client.DownloadResponse.SHA256_HEADER_NAME;

/**
 * Downloads JFrog CLI.
 * Runs inside an agent.
 */
@AllArgsConstructor
public class JFrogCliDownloader extends MasterToSlaveFileCallable<Void> {

    /**
     * The name of the file that contains the JFrog CLI binary sha256.
     * The file will help us determine if we should download an updated version or skip it.
     */
    private static final String SHA256_FILE_NAME = "sha256";

    /**
     * decoded "[RELEASE]" for the download url
     */
    private static final String RELEASE = "[RELEASE]";
    
    /**
     * Minimum valid CLI binary size in bytes (1MB).
     * Used to determine if an existing CLI installation is valid.
     */
    private static final long MIN_VALID_CLI_SIZE = 1024 * 1024;

    JenkinsProxyConfiguration proxyConfiguration;
    private String providedVersion;
    JFrogPlatformInstance instance;
    private TaskListener log;
    String repository;
    String binaryName;

    @Override
    public Void invoke(File toolLocation, VirtualChannel channel) throws IOException, InterruptedException {
        log.getLogger().println("[JFrogCliDownloader] Starting CLI download");
        
        // Ensure the tool location directory exists
        if (!toolLocation.exists()) {
            if (!toolLocation.mkdirs()) {
                throw new IOException("Failed to create tool location directory: " + toolLocation.getAbsolutePath());
            }
        }
        
        // Check if this is a fresh install or an upgrade
        File existingCli = new File(toolLocation, binaryName);
        boolean isFreshInstall = !isExistingCliValid(existingCli);
        
        if (isFreshInstall) {
            log.getLogger().println("[JFrogCliDownloader] Fresh installation detected");
            performDownloadWithLock(toolLocation);
        } else {
            log.getLogger().println("[JFrogCliDownloader] Existing CLI found - attempting upgrade");
            performDownloadWithLockForUpgrade(toolLocation, existingCli);
        }
        
        return null;
    }
    
    /**
     * Performs download for upgrade scenario with graceful fallback.
     * If the binary is locked during replacement, we skip the upgrade and use existing.
     * 
     * @param toolLocation The target directory
     * @param existingCli The existing CLI binary
     * @throws IOException If download fails for non-recoverable reasons
     * @throws InterruptedException If interrupted
     */
    private void performDownloadWithLockForUpgrade(File toolLocation, File existingCli) throws IOException, InterruptedException {
        String version = StringUtils.defaultIfBlank(providedVersion, RELEASE);
        String cliUrlSuffix = String.format("/%s/v2-jf/%s/jfrog-cli-%s/%s", repository, version, OsUtils.getOsDetails(), binaryName);

        JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(log);
        String artifactoryUrl = instance.inferArtifactoryUrl();
        
        try (ArtifactoryManager manager = new ArtifactoryManager(artifactoryUrl, 
                Secret.toString(instance.getCredentialsConfig().getUsername()),
                Secret.toString(instance.getCredentialsConfig().getPassword()), 
                Secret.toString(instance.getCredentialsConfig().getAccessToken()), buildInfoLog)) {
            
            if (proxyConfiguration.isProxyConfigured(artifactoryUrl)) {
                manager.setProxyConfiguration(proxyConfiguration);
            }
            
            String artifactorySha256 = getArtifactSha256(manager, cliUrlSuffix);
            
            if (!shouldDownloadTool(toolLocation, artifactorySha256)) {
                log.getLogger().println("[JFrogCliDownloader] CLI is up-to-date, skipping download");
                return;
            }
            
            if (version.equals(RELEASE)) {
                log.getLogger().printf("[JFrogCliDownloader] Upgrading '%s' to latest version from: %s%n", 
                                      binaryName, artifactoryUrl + cliUrlSuffix);
            } else {
                log.getLogger().printf("[JFrogCliDownloader] Upgrading '%s' to version %s from: %s%n", 
                                      binaryName, version, artifactoryUrl + cliUrlSuffix);
            }
            
            // Attempt upgrade with graceful fallback
            boolean upgradeSucceeded = performAtomicDownloadForUpgrade(manager, cliUrlSuffix, toolLocation, 
                                                                       artifactorySha256, existingCli);
            
            if (upgradeSucceeded) {
                log.getLogger().println("[JFrogCliDownloader] Upgrade completed successfully");
            } else {
                log.getLogger().println("[JFrogCliDownloader] Upgrade skipped, using existing CLI version");
            }
        }
    }
    
    /**
     * Checks if an existing CLI binary is valid and usable.
     * A valid CLI exists and has a reasonable file size (> 1MB).
     * 
     * @param cliFile The CLI binary file to check
     * @return true if CLI is valid and usable, false otherwise
     */
    private boolean isExistingCliValid(File cliFile) {
        if (!cliFile.exists()) {
            return false;
        }
        
        long fileSize = cliFile.length();
        boolean isValid = fileSize >= MIN_VALID_CLI_SIZE;
        
        if (isValid) {
            log.getLogger().println("[JFrogCliDownloader] Found valid existing CLI: " + cliFile.getAbsolutePath() + 
                                  " (size: " + (fileSize / 1024 / 1024) + "MB)");
        }
        
        return isValid;
    }
    
    /**
     * Performs the actual download operation for fresh installations.
     * 
     * @param toolLocation The target directory for CLI installation
     * @throws IOException If download fails
     * @throws InterruptedException If interrupted during download
     */
    private void performDownloadWithLock(File toolLocation) throws IOException, InterruptedException {
        // An empty string indicates the latest version.
        String version = StringUtils.defaultIfBlank(providedVersion, RELEASE);
        String cliUrlSuffix = String.format("/%s/v2-jf/%s/jfrog-cli-%s/%s", repository, version, OsUtils.getOsDetails(), binaryName);

        JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(log);

        // Downloading binary from Artifactory
        String artifactoryUrl = instance.inferArtifactoryUrl();
        try (ArtifactoryManager manager = new ArtifactoryManager(artifactoryUrl, Secret.toString(instance.getCredentialsConfig().getUsername()),
                Secret.toString(instance.getCredentialsConfig().getPassword()), Secret.toString(instance.getCredentialsConfig().getAccessToken()), buildInfoLog)) {
            if (proxyConfiguration.isProxyConfigured(artifactoryUrl)) {
                manager.setProxyConfiguration(proxyConfiguration);
            }
            // Getting updated cli binary's sha256 from Artifactory.
            String artifactorySha256 = getArtifactSha256(manager, cliUrlSuffix);
            if (shouldDownloadTool(toolLocation, artifactorySha256)) {
                if (version.equals(RELEASE)) {
                    log.getLogger().printf("[JFrogCliDownloader] Download '%s' latest version from: %s%n", binaryName, artifactoryUrl + cliUrlSuffix);
                } else {
                    log.getLogger().printf("[JFrogCliDownloader] Download '%s' version %s from: %s%n", binaryName, version, artifactoryUrl + cliUrlSuffix);
                }
                
                // Download using atomic file operations for reliability
                performAtomicDownload(manager, cliUrlSuffix, toolLocation, artifactorySha256);
                
            } else {
                log.getLogger().println("[JFrogCliDownloader] CLI is up-to-date, skipping download");
            }
        }
        
        log.getLogger().println("[JFrogCliDownloader] Download completed successfully");
    }
    
    /**
     * Performs atomic download operations for reliable file installation.
     * Used for fresh installations where we MUST succeed.
     * 
     * APPROACH:
     * 1. Generate unique temporary file name to avoid conflicts
     * 2. Download to temporary file
     * 3. Verify download integrity
     * 4. Atomic move from temp to final location (with retry for Windows)
     * 5. Set executable permissions
     * 6. Create SHA256 verification file
     * 7. Cleanup temporary file on any failure
     * 
     * @param manager ArtifactoryManager for download operations
     * @param cliUrlSuffix URL suffix for the CLI binary
     * @param toolLocation Target directory for installation
     * @param artifactorySha256 Expected SHA256 hash for verification
     * @throws IOException If download or file operations fail
     */
    private void performAtomicDownload(ArtifactoryManager manager, String cliUrlSuffix, 
                                     File toolLocation, String artifactorySha256) throws IOException {
        
        String stageName = getStageNameFromThread();
        String tempFileName = binaryName + ".tmp." + 
                             stageName + "." +
                             System.currentTimeMillis() + "." + 
                             Thread.currentThread().getId() + "." +
                             System.nanoTime();
        
        File temporaryDownloadFile = new File(toolLocation, tempFileName);
        File finalCliExecutable = new File(toolLocation, binaryName);
        
        log.getLogger().println("[JFrogCliDownloader] Temporary download file: " + temporaryDownloadFile.getAbsolutePath());
        log.getLogger().println("[JFrogCliDownloader] Final CLI executable: " + finalCliExecutable.getAbsolutePath());
        
        try {
            // Download to temporary file
            log.getLogger().println("[JFrogCliDownloader] Downloading to temporary file");
            manager.downloadToFile(cliUrlSuffix, temporaryDownloadFile.getPath());
            
            // Verify download integrity
            log.getLogger().println("[JFrogCliDownloader] Verifying download integrity");
            if (!temporaryDownloadFile.exists()) {
                throw new IOException("Downloaded file doesn't exist: " + temporaryDownloadFile.getAbsolutePath());
            }
            
            long fileSize = temporaryDownloadFile.length();
            if (fileSize == 0) {
                throw new IOException("Downloaded file is empty: " + temporaryDownloadFile.getAbsolutePath());
            }
            
            log.getLogger().println("[JFrogCliDownloader] Download verified: " + (fileSize / 1024 / 1024) + "MB");
            
            // Move to final location using NIO with retry for Windows file locking issues
            log.getLogger().println("[JFrogCliDownloader] Moving to final location");
            moveFileWithRetry(temporaryDownloadFile, finalCliExecutable);
            
            // Set executable permissions on final CLI binary
            log.getLogger().println("[JFrogCliDownloader] Setting executable permissions");
            if (!finalCliExecutable.setExecutable(true)) {
                throw new IOException("No permission to add execution permission to binary: " + finalCliExecutable.getAbsolutePath());
            }
            
            // Create SHA256 verification file
            log.getLogger().println("[JFrogCliDownloader] Creating SHA256 verification file");
            createSha256File(toolLocation, artifactorySha256);
            
            log.getLogger().println("[JFrogCliDownloader] Download and installation completed successfully");
            
        } catch (Exception e) {
            // Cleanup temporary file on failure
            log.getLogger().println("[JFrogCliDownloader] Download failed, cleaning up temporary file");
            cleanupTempFile(temporaryDownloadFile);
            throw e;
        }
    }
    
    /**
     * Performs atomic download for upgrade scenario with graceful fallback.
     * If the target binary is locked (in use), this method returns false to indicate
     * the upgrade was skipped, allowing the caller to use the existing CLI version.
     * 
     * @param manager ArtifactoryManager for download operations
     * @param cliUrlSuffix URL suffix for the CLI binary
     * @param toolLocation Target directory for installation
     * @param artifactorySha256 Expected SHA256 hash for verification
     * @param existingCli The existing CLI binary file
     * @return true if upgrade succeeded, false if skipped due to file locking
     * @throws IOException If download fails for non-recoverable reasons (not file locking)
     */
    private boolean performAtomicDownloadForUpgrade(ArtifactoryManager manager, String cliUrlSuffix, 
                                                   File toolLocation, String artifactorySha256,
                                                   File existingCli) throws IOException {
        
        String stageName = getStageNameFromThread();
        String tempFileName = binaryName + ".tmp." + 
                             stageName + "." +
                             System.currentTimeMillis() + "." + 
                             Thread.currentThread().getId() + "." +
                             System.nanoTime();
        
        File temporaryDownloadFile = new File(toolLocation, tempFileName);
        File finalCliExecutable = new File(toolLocation, binaryName);
        
        log.getLogger().println("[JFrogCliDownloader] Temporary download file: " + temporaryDownloadFile.getAbsolutePath());
        log.getLogger().println("[JFrogCliDownloader] Final CLI executable: " + finalCliExecutable.getAbsolutePath());
        
        try {
            // Download to temporary file
            log.getLogger().println("[JFrogCliDownloader] Downloading to temporary file");
            manager.downloadToFile(cliUrlSuffix, temporaryDownloadFile.getPath());
            
            // Verify download integrity
            log.getLogger().println("[JFrogCliDownloader] Verifying download integrity");
            if (!temporaryDownloadFile.exists()) {
                throw new IOException("Downloaded file doesn't exist: " + temporaryDownloadFile.getAbsolutePath());
            }
            
            long fileSize = temporaryDownloadFile.length();
            if (fileSize == 0) {
                throw new IOException("Downloaded file is empty: " + temporaryDownloadFile.getAbsolutePath());
            }
            
            log.getLogger().println("[JFrogCliDownloader] Download verified: " + (fileSize / 1024 / 1024) + "MB");
            
            // Try to move to final location - for upgrades, gracefully handle file locking
            log.getLogger().println("[JFrogCliDownloader] Attempting to replace existing CLI");
            boolean moveSucceeded = tryMoveFileForUpgrade(temporaryDownloadFile, finalCliExecutable);
            
            if (!moveSucceeded) {
                // File is locked - skip upgrade, use existing
                log.getLogger().println("[JFrogCliDownloader] WARNING: Existing CLI is in use by another process. " +
                                      "Upgrade skipped. Using existing version. Upgrade will be attempted in next build.");
                cleanupTempFile(temporaryDownloadFile);
                return false;
            }
            
            // Set executable permissions on final CLI binary
            log.getLogger().println("[JFrogCliDownloader] Setting executable permissions");
            if (!finalCliExecutable.setExecutable(true)) {
                throw new IOException("No permission to add execution permission to binary: " + finalCliExecutable.getAbsolutePath());
            }
            
            // Create SHA256 verification file
            log.getLogger().println("[JFrogCliDownloader] Creating SHA256 verification file");
            createSha256File(toolLocation, artifactorySha256);
            
            return true;
            
        } catch (IOException e) {
            // For upgrade failures, check if we can fall back to existing
            if (isWindowsTarget() && isFileLockingError(e) && isExistingCliValid(existingCli)) {
                log.getLogger().println("[JFrogCliDownloader] WARNING: Upgrade failed due to file locking. " +
                                      "Using existing CLI version. Upgrade will be attempted in next build.");
                cleanupTempFile(temporaryDownloadFile);
                return false;
            }
            
            // Non-recoverable error
            cleanupTempFile(temporaryDownloadFile);
            throw e;
        }
    }
    
    /**
     * Attempts to move a file for upgrade scenario.
     * Returns false if file is locked (instead of throwing), allowing graceful fallback.
     * 
     * @param source Source file to move
     * @param target Target location
     * @return true if move succeeded, false if target is locked
     * @throws IOException If move fails for non-locking reasons
     */
    private boolean tryMoveFileForUpgrade(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.getLogger().println("[JFrogCliDownloader] File moved successfully to: " + target.getAbsolutePath());
            return true;
        } catch (IOException e) {
            if (isFileLockingError(e)) {
                if (!isWindowsTarget()) {
                    throw e;
                }
                log.getLogger().println("[JFrogCliDownloader] Target file is locked: " + e.getMessage());
                return false;
            }
            throw e;
        }
    }
    
    /**
     * Checks if an exception is caused by Windows file locking.
     * 
     * @param e The exception to check
     * @return true if this is a file locking error
     */
    private boolean isFileLockingError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof AccessDeniedException) {
                return true;
            }
            if (current instanceof FileSystemException) {
                String reason = ((FileSystemException) current).getReason();
                if (containsLockingMessage(reason)) {
                    return true;
                }
            }
            if (containsLockingMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsLockingMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("being used by another process") ||
                message.contains("Access is denied") ||
                message.contains("cannot access the file") ||
                message.contains("locked") ||
                message.contains("in use");
    }
    
    /**
     * Moves a file to the target location with retry logic for Windows file locking issues.
     * Uses Java NIO Files.move with REPLACE_EXISTING option for atomic operation.
     * On Windows, if the target file is locked (e.g., being scanned by antivirus),
     * this method will retry with exponential backoff.
     * 
     * @param source Source file to move
     * @param target Target location
     * @throws IOException If move fails after all retries
     */
    private void moveFileWithRetry(File source, File target) throws IOException {
        int maxRetries = 5;
        long retryDelayMs = 1000;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Use Files.move with REPLACE_EXISTING for atomic replacement
                // Note: ATOMIC_MOVE is not always supported on Windows, so we use REPLACE_EXISTING
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.getLogger().println("[JFrogCliDownloader] File moved successfully to: " + target.getAbsolutePath());
                return;
            } catch (IOException e) {
                boolean isLastAttempt = (attempt == maxRetries);
                String errorMsg = e.getMessage();
                
                // Check if this is a Windows file locking issue
                boolean isFileLockingIssue = errorMsg != null && 
                    (errorMsg.contains("being used by another process") ||
                     errorMsg.contains("Access is denied") ||
                     errorMsg.contains("cannot access the file"));
                
                if (isFileLockingIssue && !isLastAttempt) {
                    log.getLogger().println("[JFrogCliDownloader] File locked, retrying in " + 
                                          retryDelayMs + "ms (attempt " + attempt + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting to retry file move", ie);
                    }
                    // Exponential backoff
                    retryDelayMs *= 2;
                } else {
                    throw new IOException("Failed to move file from " + source.getAbsolutePath() + 
                                        " to " + target.getAbsolutePath() + 
                                        " after " + attempt + " attempts: " + errorMsg, e);
                }
            }
        }
    }
    
    /**
     * Safely cleans up temporary files.
     * 
     * @param tempFile Temporary file to delete
     */
    private void cleanupTempFile(File tempFile) {
        try {
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.delete()) {
                    log.getLogger().println("[JFrogCliDownloader] Cleaned up temporary file: " + tempFile.getAbsolutePath());
                } else {
                    log.getLogger().println("[JFrogCliDownloader] Failed to delete temporary file: " + tempFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.getLogger().println("[JFrogCliDownloader] Error during cleanup: " + e.getMessage());
        }
    }

    private static void createSha256File(File toolLocation, String artifactorySha256) throws IOException {
        if (StringUtils.isBlank(artifactorySha256)) {
            return;
        }
        File file = new File(toolLocation, SHA256_FILE_NAME);
        Files.write(file.toPath(), artifactorySha256.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * We should skip the download if the tool's directory already contains the specific version, otherwise we should download it.
     * A file named 'sha256' contains the specific binary sha256.
     * If the file sha256 has not changed, we will skip the download, otherwise we will download and overwrite the existing files.
     *
     * @param toolLocation      - expected location of the tool on the fileSystem.
     * @param artifactorySha256 - sha256 of the expected file in artifactory.
     */
    private static boolean shouldDownloadTool(File toolLocation, String artifactorySha256) throws IOException {
        // In case no sha256 was provided (for example when the users blocks headers) download the tool.
        if (artifactorySha256.isEmpty()) {
            return true;
        }
        // Looking for the sha256 file in the tool directory.
        Path path = toolLocation.toPath().resolve(SHA256_FILE_NAME);
        if (!Files.exists(path)) {
            return true;
        }
        String fileContent = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return !StringUtils.equals(fileContent, artifactorySha256);
    }

    /**
     * Send REST request to Artifactory to get binary's sha256.
     *
     * @param manager      - internal Artifactory Java manager.
     * @param cliUrlSuffix - path to the specific JFrog CLI version in Artifactory, will be sent to Artifactory in the request.
     * @return binary's sha256
     * @throws IOException in case of any I/O error.
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
        return StringUtils.EMPTY;
    }
    
    /**
     * Extract stage name from current thread for better temp file naming.
     * This helps identify which parallel stage is performing the download.
     * 
     * @return sanitized stage name or "unknown" if not determinable
     */
    private String getStageNameFromThread() {
        try {
            String threadName = Thread.currentThread().getName();
            // Jenkins thread names often contain stage information
            // Examples: "Branch indexing", "In Parallel 1", "Parallel Stage 2"
            if (threadName.contains("Parallel")) {
                // Extract stage info from thread name
                String stageName = threadName.replaceAll("[^a-zA-Z0-9]", "_");
                return stageName.length() > 20 ? stageName.substring(0, 20) : stageName;
            }
            // Fallback to thread ID if no recognizable stage name
            return "thread" + Thread.currentThread().getId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Determine whether the target CLI binary is Windows.
     */
    private boolean isWindowsTarget() {
        return binaryName != null && binaryName.toLowerCase().endsWith(".exe");
    }
}
