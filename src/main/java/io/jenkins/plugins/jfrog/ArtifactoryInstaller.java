package io.jenkins.plugins.jfrog;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

/**
 * Download and install JFrog CLI from a remote artifactory (instead of the default 'releases.jfrog.io')
 *
 * @author gail
 */
@SuppressWarnings("unused")
public class ArtifactoryInstaller extends BinaryInstaller {

    public final String serverId;
    public final String repository;

    @DataBoundConstructor
    public ArtifactoryInstaller(String id, String repository) {
        super(null);
        this.serverId = id;
        this.repository = repository;
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        JFrogPlatformInstance server = getSpecificServer(serverId);
        if (server == null) {
            throw new IOException("Server id '" + serverId + "' doesn't exists.");
        }
        String binaryName = Utils.getJfrogCliBinaryName(!node.createLauncher(log).isUnix());
        return performJfrogCliInstallation(getToolLocation(tool, node), log, StringUtils.EMPTY, server, repository, binaryName);
    }

    /**
     * Look for all configured server ids and return the specific one matched the given id.
     */
    private JFrogPlatformInstance getSpecificServer(String id) {
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
    }
}