package io.jenkins.plugins.jfrog;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Download and install Jfrog CLI from 'releases.jfrog.io'.
 *
 * @author gail
 */
public class ReleasesInstaller extends BinaryInstaller {
    public final String id;
    public static final String RELEASES_ARTIFACTORY_URL = "https://releases.jfrog.io/artifactory";
    public static final String REPOSITORY = "jfrog-cli";

    @DataBoundConstructor
    public ReleasesInstaller(String id) {
        super(null);
        this.id = id;
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        JFrogPlatformInstance instance = createReleasesPlatformInstance();
        String binaryName = Utils.getJfrogCliBinaryName(!node.createLauncher(log).isUnix());
        return performJfrogCliInstallation(getToolLocation(tool, node), log, id, instance, REPOSITORY, binaryName);
    }

    /**
     * @return The JFrogPlatformInstance matches 'Releases.jfrog.io' with only the relevant Artifactory URL and no credentials.
     */
    private JFrogPlatformInstance createReleasesPlatformInstance() {
        CredentialsConfig emptyCred = new CredentialsConfig(StringUtils.EMPTY, Credentials.EMPTY_CREDENTIALS);
        return new JFrogPlatformInstance(StringUtils.EMPTY, StringUtils.EMPTY, emptyCred, RELEASES_ARTIFACTORY_URL, StringUtils.EMPTY, StringUtils.EMPTY);
    }

    @Extension
    @SuppressWarnings("unused")
    public static final class DescriptorImpl extends BinaryInstaller.DescriptorImpl<ReleasesInstaller> {
        @Nonnull
        public String getDisplayName() {
            return "Install from releases.jfrog.io";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == JfrogInstallation.class;
        }
    }
}