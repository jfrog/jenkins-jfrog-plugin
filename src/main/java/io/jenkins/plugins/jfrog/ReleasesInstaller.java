package io.jenkins.plugins.jfrog;

import hudson.Extension;
import hudson.tools.ToolInstallation;
import hudson.util.FormValidation;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.Nonnull;

/**
 * Download and install Jfrog CLI from 'releases.jfrog.io'.
 *
 * @author gail
 */
public class ReleasesInstaller extends ArtifactoryInstaller {
    private static final String RELEASES_ARTIFACTORY_URL = "https://releases.jfrog.io/artifactory";
    private static final String RELEASES_REPOSITORY = "jfrog-cli";

    @DataBoundConstructor
    public ReleasesInstaller() {
        super("", RELEASES_REPOSITORY, "");
    }

    @DataBoundSetter
    public void setVersion(String version) {
        super.setVersion(version);
    }

    @Override
    public String getRepository() {
        return RELEASES_REPOSITORY;
    }

    /**
     * @return The JFrogPlatformInstance matches 'Releases.jfrog.io' with only the relevant Artifactory URL and no credentials.
     */

    @Override
    JFrogPlatformInstance getSpecificServer(String id) {
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

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckVersion(@QueryParameter String version) {
            return validateCliVersion(version);
        }
    }
}
