package io.jenkins.plugins.jfrog;

import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.jfrog.build.client.ProxyConfiguration;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.jenkins.plugins.jfrog.Utils.createProxyConfiguration;
import static org.jfrog.build.client.DownloadResponse.SHA256_HEADER_NAME;


/**
 * Installer for JFrog CLI binary.
 *
 * @author gail
 */
public abstract class BinaryInstaller extends ToolInstaller {
    /**
     * decoded "[RELEASE]" for thee download url
     */
    private static final String RELEASE = "[RELEASE]";

    /**
     * The name of the file that contains the JFrog CLI binary sha256.
     * The file will help us determine if we should download an updated version or skip it.
     */
    private static final String SHA256_FILE_NAME = "sha256";

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
     * Download and locate the JFrog CLI binary in the specific build home directory.
     *
     * @param toolLocation       - Location of the tool directory on the fileSystem
     * @param log                - Job task listener
     * @param providedVersion    - Version provided by the user. empty string indicates the latest version
     * @param instance           - JFrogPlatformInstance contains url and credentials needed for the downloading operation
     * @param repository         - Identifies the repository in Artifactory where the CLIs binary is stored
     * @param binaryName         - 'jf' or 'jf.exe'
     * @param proxyConfiguration - The proxy configuration or null if not configured
     * @throws IOException in case of any I/O error.
     */
    private static void downloadJfrogCli(File toolLocation, TaskListener log, String providedVersion,
                                         JFrogPlatformInstance instance, String repository, String binaryName, ProxyConfiguration proxyConfiguration) throws IOException {
        // An empty string indicates the latest version.
        String version = StringUtils.defaultIfBlank(providedVersion, RELEASE);
        String cliUrlSuffix = String.format("/%s/v2-jf/%s/jfrog-cli-%s/%s", repository, version, OsUtils.getOsDetails(), binaryName);

        JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(log);

        // Downloading binary from Artifactory
        try (ArtifactoryManager manager = new ArtifactoryManager(instance.inferArtifactoryUrl(), Secret.toString(instance.getCredentialsConfig().getUsername()),
                Secret.toString(instance.getCredentialsConfig().getPassword()), Secret.toString(instance.getCredentialsConfig().getAccessToken()), buildInfoLog)) {
            if (proxyConfiguration != null) {
                manager.setProxyConfiguration(proxyConfiguration);
            }
            // Getting updated cli binary's sha256 form Artifactory.
            String artifactorySha256 = getArtifactSha256(manager, cliUrlSuffix);
            if (shouldDownloadTool(toolLocation, artifactorySha256)) {
                if (version.equals(RELEASE)) {
                    log.getLogger().printf("Download '%s' latest version from: %s%n", binaryName, instance.inferArtifactoryUrl() + cliUrlSuffix);
                } else {
                    log.getLogger().printf("Download '%s' version %s from: %s%n", binaryName, version, instance.inferArtifactoryUrl() + cliUrlSuffix);
                }
                File downloadResponse = manager.downloadToFile(cliUrlSuffix, new File(toolLocation, binaryName).getPath());
                if (!downloadResponse.setExecutable(true)) {
                    throw new IOException("No permission to add execution permission to binary");
                }
                createSha256File(toolLocation, artifactorySha256);
            }
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

    public static FilePath performJfrogCliInstallation(FilePath toolLocation, TaskListener log, String version, JFrogPlatformInstance instance, String repository, String binaryName) throws IOException, InterruptedException {
        ProxyConfiguration proxyConfiguration = createProxyConfiguration();
        // Download Jfrog CLI binary
        toolLocation.act(new MasterToSlaveFileCallable<Void>() {
            @Override
            public Void invoke(File f, VirtualChannel channel) throws IOException {
                downloadJfrogCli(f, log, version, instance, repository, binaryName, proxyConfiguration);
                return null;
            }
        });
        return toolLocation;
    }
}

