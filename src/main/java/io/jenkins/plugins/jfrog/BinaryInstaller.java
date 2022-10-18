package io.jenkins.plugins.jfrog;

import hudson.FilePath;
import hudson.model.DownloadService;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import io.jenkins.plugins.jfrog.artifactoryclient.ArtifactoryClient;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import jenkins.MasterToSlaveFileCallable;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;


/**
 * Installer for JFrog CLI binary.
 *
 * @author gail
 */
public abstract class BinaryInstaller extends ToolInstaller {
    /**
     * decoded "[RELEASE]" for thee download url
     */
    private static final String RELEASE = "%5BRELEASE%5D";

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

        @SuppressWarnings("deprecation") // intentionally adding dynamic item here
        protected DescriptorImpl() {
            DownloadService.Downloadable.all().add(createDownloadable());
        }

        /**
         * function that creates a {@link DownloadService.Downloadable}.
         *
         * @return a downloadable object
         */
        public DownloadService.Downloadable createDownloadable() {
            return new DownloadService.Downloadable(getId()) {
                @Override
                public JSONObject reduce(List<JSONObject> jsonList) {
                    return super.reduce(jsonList);

                }
            };
        }

        /**
         * This ID needs to be unique, and needs to match the ID token in the JSON update file.
         * <p>
         * By default we use the fully-qualified class name of the {@link BinaryInstaller} subtype.
         */
        @Override
        public String getId() {
            return clazz.getName().replace('$', '.');
        }

    }

    /**
     * Download and locate the JFrog CLI binary in the specific build home directory.
     *
     * @param toolLocation    location of the tool directory on the fileSystem.
     * @param log             job task listener.
     * @param providedVersion version provided by the user. empty string indicates the latest version.
     * @param instance        JFrogPlatformInstance contains url and credentials needed for the downloading operation.
     * @param repository      identifies the repository in Artifactory where the CLIs binary is stored.
     * @throws IOException in case of any I/O error.
     */
    private static void downloadJfrogCli(File toolLocation, TaskListener log, String providedVersion, JFrogPlatformInstance instance, String repository, String binaryName) throws IOException {
        // An empty string indicates the latest version.
        String version = StringUtils.defaultIfBlank(providedVersion, RELEASE);
        String cliUrlSuffix = String.format("/%s/v2-jf/%s/jfrog-cli-%s/%s", repository, version, OsUtils.getOsDetails(), binaryName);

        // Getting credentials
        Credentials credentials = new Credentials();
        if (instance.getCredentialsConfig() != null) {
            credentials = PluginsUtils.credentialsLookup(instance.getCredentialsConfig().getCredentialsId(), null);
        }

        // Downloading binary from Artifactory
        try (ArtifactoryClient client = new ArtifactoryClient(instance.getArtifactoryUrl(), credentials.getPlainTextUsername(), credentials.getPlainTextPassword(), credentials.getPlainTextAccessToken(), null, log)) {
            if (shouldDownloadTool(client, cliUrlSuffix, toolLocation)) {
                try (CloseableHttpResponse downloadResponse = client.download(cliUrlSuffix)) {
                    InputStream input = downloadResponse.getEntity().getContent();
                    if (downloadResponse.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                        throw new IOException("Failed downloading JFrog CLI binary: " + downloadResponse.getStatusLine());
                    }
                    if (version.equals(RELEASE)) {
                        log.getLogger().printf("Download '%s' latest version from: %s%n", binaryName, instance.getArtifactoryUrl() + cliUrlSuffix);
                    } else {
                        log.getLogger().printf("Download '%s' version %s from: %s%n", binaryName, version, instance.getArtifactoryUrl() + cliUrlSuffix);
                    }
                    File file = new File(toolLocation, binaryName);
                    Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if (!file.setExecutable(true)) {
                        throw new IOException("No permission to add execution permission to binary");
                    }
                    createSha256File(toolLocation, client, cliUrlSuffix);
                }
            }
        }
    }

    private static void createSha256File(File toolLocation, ArtifactoryClient client, String cliUrlSuffix) throws IOException {
        // Getting cli binary's sha256 form Artifactory.
        String artifactorySha256 = getArtifactSha256(client, cliUrlSuffix);
        File file = new File(toolLocation, SHA256_FILE_NAME);
        FileUtils.writeStringToFile(file, artifactorySha256, StandardCharsets.UTF_8);
    }

    /**
     * We should skip the download if the tool's directory already contains the specific version, otherwise we should download it.
     * A file named 'sha256' contains the specific binary sha256.
     * If the file sha256 has not changed, we will skip the download, otherwise we will download and overwrite the existing files.
     *
     * @param client       - internal Artifactory Java client.
     * @param cliUrlSuffix - path to the specific JFrog CLI version in Artifactory, will be sent to Artifactory in the HEAD request.
     * @param toolLocation - expected location of the tool on the fileSystem.
     */
    private static boolean shouldDownloadTool(ArtifactoryClient client, String cliUrlSuffix, File toolLocation) throws IOException {
        // Looking for the sha256 file in the tool directory
        Path path = toolLocation.toPath().resolve(SHA256_FILE_NAME);
        if (!Files.exists(path)) {
            return true;
        }
        // Getting cli binary's sha256 form Artifactory.
        String artifactorySha256 = getArtifactSha256(client, cliUrlSuffix);
        String fileContent = FileUtils.readFileToString(path.toFile(), StandardCharsets.UTF_8);
        return !StringUtils.equals(fileContent, artifactorySha256);
    }

    /**
     * Send REST request to Artifactory to get binary's sha256.
     *
     * @param client       - internal Artifactory Java client.
     * @param cliUrlSuffix - path to the specific JFrog CLI version in Artifactory, will be sent to Artifactory in the request.
     * @return binary's sha256
     * @throws IOException in case of any I/O error.
     */
    private static String getArtifactSha256(ArtifactoryClient client, String cliUrlSuffix) throws IOException {
        try (CloseableHttpResponse response = client.head(cliUrlSuffix)) {
            Header[] sha256Headers = response.getHeaders("X-Checksum-Sha256");
            if (sha256Headers.length == 0) {
                return StringUtils.EMPTY;
            }
            return sha256Headers[0].getValue();
        }
    }

    public static FilePath performJfrogCliInstallation(FilePath toolLocation, TaskListener log, String version, JFrogPlatformInstance instance, String repository, String binaryName) throws IOException, InterruptedException {
        // Download Jfrog CLI binary
        toolLocation.act(new MasterToSlaveFileCallable<Void>() {
            @Override
            public Void invoke(File f, VirtualChannel channel) throws IOException {
                downloadJfrogCli(f, log, version, instance, repository, binaryName);
                return null;
            }
        });
        return toolLocation;
    }
}

