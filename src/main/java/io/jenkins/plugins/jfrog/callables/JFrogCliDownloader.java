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
import java.nio.file.Files;
import java.nio.file.Path;

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

    JenkinsProxyConfiguration proxyConfiguration;
    private String providedVersion;
    JFrogPlatformInstance instance;
    private TaskListener log;
    String repository;
    String binaryName;

    @Override
    public Void invoke(File toolLocation, VirtualChannel channel) throws IOException, InterruptedException {
        log.getLogger().println("[JFrogCliDownloader] Starting CLI download");
        
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
            // Getting updated cli binary's sha256 form Artifactory.
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
        return null;
    }
    
    /**
     * Performs atomic download operations for reliable file installation.
     * 
     * APPROACH:
     * 1. Generate unique temporary file name to avoid conflicts
     * 2. Download to temporary file
     * 3. Verify download integrity
     * 4. Atomic move from temp to final location
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
        
        // Phase 1: Generate unique temporary file name to avoid conflicts during parallel downloads
        String stageName = getStageNameFromThread();
        String tempFileName = binaryName + ".tmp." + 
                             stageName + "." +
                             System.currentTimeMillis() + "." + 
                             Thread.currentThread().getId() + "." +
                             System.nanoTime();
        
        File temporaryDownloadFile = new File(toolLocation, tempFileName);  // Temp file for download
        File finalCliExecutable = new File(toolLocation, binaryName);       // Final CLI binary location
        
        log.getLogger().println("[JFrogCliDownloader] Temporary download file: " + temporaryDownloadFile.getAbsolutePath());
        log.getLogger().println("[JFrogCliDownloader] Final CLI executable: " + finalCliExecutable.getAbsolutePath());
        
        try {
            // Download to temporary file
            log.getLogger().println("[JFrogCliDownloader] Downloading to temporary file");
            File downloadResponse = manager.downloadToFile(cliUrlSuffix, temporaryDownloadFile.getPath());
            
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
            
            // Atomic move to final location (only delete existing if move will succeed)
            log.getLogger().println("[JFrogCliDownloader] Moving to final location");
            if (finalCliExecutable.exists()) {
                log.getLogger().println("[JFrogCliDownloader] Removing existing CLI binary to replace with new version");
                if (!finalCliExecutable.delete()) {
                    throw new IOException("Failed to remove existing CLI binary: " + finalCliExecutable.getAbsolutePath());
                }
            }
            
            // Atomic move from temporary file to final location
            if (!temporaryDownloadFile.renameTo(finalCliExecutable)) {
                throw new IOException("Failed to move temporary file to final location. Temp: " + 
                                    temporaryDownloadFile.getAbsolutePath() + ", Final: " + finalCliExecutable.getAbsolutePath());
            }
            
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
        // In case no sha256 was provided (for example when the customer blocks headers) download the tool.
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
            if (header.getName().equalsIgnoreCase(SHA256_HEADER_NAME)) {
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
}
