package io.jenkins.plugins.jfrog;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.util.FormValidation;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.build.client.Version;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Download and install JFrog CLI from a remote artifactory (instead of the default 'releases.jfrog.io')
 *
 * @author gail
 */
@SuppressWarnings("unused")
public class ArtifactoryInstaller extends BinaryInstaller {
    private static final Version MIN_CLI_VERSION = new Version("2.6.1");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    static final String BAD_VERSION_PATTERN_ERROR = "Version must be in the form of X.X.X";
    static final String LOW_VERSION_PATTERN_ERROR = "The provided JFrog CLI version must be at least " + MIN_CLI_VERSION;

    final String serverId;
    final String repository;
    final String version;

    @DataBoundConstructor
    public ArtifactoryInstaller(String serverId, String repository, String version) {
        super(null);
        this.serverId = serverId;
        this.repository = StringUtils.trim(repository);
        this.version = StringUtils.trim(version);
    }

    public String getServerId() {
        return serverId;
    }

    public String getRepository() {
        return repository;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        JFrogPlatformInstance server = getSpecificServer(serverId);
        if (server == null) {
            throw new IOException("Server id '" + serverId + "' doesn't exists.");
        }
        String binaryName = Utils.getJfrogCliBinaryName(!node.createLauncher(log).isUnix());
        return performJfrogCliInstallation(getToolLocation(tool, node), log, version, server, repository, binaryName);
    }

    /**
     * Look for all configured server ids and return the specific one matched the given id.
     */
    JFrogPlatformInstance getSpecificServer(String id) {
        List<JFrogPlatformInstance> jfrogInstances = JFrogPlatformBuilder.getJFrogPlatformInstances();
        if (jfrogInstances != null && jfrogInstances.size() > 0) {
            for (JFrogPlatformInstance jfrogPlatformInstance : jfrogInstances) {
                if (jfrogPlatformInstance.getId().equals(id)) {
                    // Getting credentials
                    // We sent a null item to 'credentialsLookup' since we do not know which job will be running at the time of installation, and we don't have the relevant 'Run' object yet.
                    // Therefore, when downloading the CLI from the user's Artifactory remote repository, we should use global credentials.
                    String credentialsId = jfrogPlatformInstance.getCredentialsConfig().getCredentialsId();
                    jfrogPlatformInstance.setCredentialsConfig(new CredentialsConfig(credentialsId, PluginsUtils.credentialsLookup(credentialsId, null)));
                    return jfrogPlatformInstance;
                }
            }
        }
        return null;
    }

    /**
     * Make on-the-fly validation that the provided CLI version is empty or at least 2.6.1.
     *
     * @param version - Requested JFrog CLI version
     * @return the validation results.
     */
    static FormValidation validateCliVersion(@QueryParameter String version) {
        if (StringUtils.isBlank(version)) {
            return FormValidation.ok();
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            return FormValidation.error(BAD_VERSION_PATTERN_ERROR);
        }
        if (!new Version(version).isAtLeast(MIN_CLI_VERSION)) {
            return FormValidation.error(LOW_VERSION_PATTERN_ERROR);
        }
        return FormValidation.ok();
    }

    @Extension
    public static final class DescriptorImpl extends BinaryInstaller.DescriptorImpl<ArtifactoryInstaller> {
        @Nonnull
        public String getDisplayName() {
            return "Install from Artifactory";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == JfrogInstallation.class;
        }

        /**
         * Necessary for displaying all configured server Ids. Used in the Jelly to show the server IDs.
         *
         * @return All pre configured servers Ids
         */
        public List<JFrogPlatformInstance> getServerIds() {
            return JFrogPlatformBuilder.getJFrogPlatformInstances();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckRepository(@QueryParameter String repository) {
            if (StringUtils.isBlank(repository)) {
                return FormValidation.error("Required");
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckVersion(@QueryParameter String version) {
            return validateCliVersion(version);
        }
    }
}
