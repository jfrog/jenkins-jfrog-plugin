package io.jenkins.plugins.jfrog.maven;

import hudson.Extension;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Maven Project Build Settings config for native Artifactory publish.
 * Capture/deploy runs in Maven's JVM via {@link MavenNativeExtractorEnvironment}.
 */
public class MavenArtifactoryReporter extends MavenReporter {

    private static final int MAX_FIELD_LENGTH = 200;
    private static final String REPO_PATTERN = "^[\\w$][\\w./${}-]*$";

    private String serverId;
    private String artifactoryRepo;
    private boolean deployArtifacts = true;
    private String buildName;
    private String buildNumber;

    @DataBoundConstructor
    public MavenArtifactoryReporter() {
    }

    public String getServerId() {
        return serverId;
    }

    @DataBoundSetter
    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getArtifactoryRepo() {
        return artifactoryRepo;
    }

    @DataBoundSetter
    public void setArtifactoryRepo(String artifactoryRepo) {
        this.artifactoryRepo = artifactoryRepo;
    }

    public boolean isDeployArtifacts() {
        return deployArtifacts;
    }

    @DataBoundSetter
    public void setDeployArtifacts(boolean deployArtifacts) {
        this.deployArtifacts = deployArtifacts;
    }

    public String getBuildName() {
        return buildName;
    }

    @DataBoundSetter
    public void setBuildName(String buildName) {
        this.buildName = buildName;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    @DataBoundSetter
    public void setBuildNumber(String buildNumber) {
        this.buildNumber = buildNumber;
    }

    public JFrogPlatformInstance resolveServer() {
        return findServer(serverId);
    }

    static JFrogPlatformInstance findServer(String id) {
        List<JFrogPlatformInstance> instances = JFrogPlatformBuilder.getJFrogPlatformInstances();
        if (instances == null || StringUtils.isBlank(id)) {
            return null;
        }
        return instances.stream()
                .filter(instance -> StringUtils.equals(instance.getId(), id))
                .findFirst()
                .orElse(null);
    }

    @Extension(optional = true)
    @Symbol("jfrogMavenArtifactory")
    public static final class DescriptorImpl extends MavenReporterDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "JFrog Artifactory (Maven Project)";
        }

        @POST
        @SuppressWarnings("unused")
        public ListBoxModel doFillServerIdItems(@AncestorInPath Item item) {
            checkConfigurePermission(item);
            ListBoxModel items = new ListBoxModel();
            items.add("— Select a server —", "");
            List<JFrogPlatformInstance> instances = JFrogPlatformBuilder.getJFrogPlatformInstances();
            if (instances != null) {
                for (JFrogPlatformInstance instance : instances) {
                    items.add(instance.getId(), instance.getId());
                }
            }
            return items;
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckServerId(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                List<JFrogPlatformInstance> instances = JFrogPlatformBuilder.getJFrogPlatformInstances();
                if (instances == null || instances.isEmpty()) {
                    return FormValidation.error("No JFrog Platform instances configured. Add one under Manage Jenkins → System.");
                }
                return FormValidation.error("A JFrog Platform server must be selected");
            }
            if (findServer(value) == null) {
                return FormValidation.error("Unknown JFrog Platform server: " + value);
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckArtifactoryRepo(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                return FormValidation.error("Repository must not be empty");
            }
            if (value.length() > MAX_FIELD_LENGTH) {
                return FormValidation.error("Repository path too long");
            }
            if (value.contains("..") || value.contains("\\") || !value.matches(REPO_PATTERN)) {
                return FormValidation.error("Repository may contain letters, digits, '.', '_', '-', '/', and ${ENV} only");
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckBuildName(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "Build name");
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckBuildNumber(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "Build number");
        }

        private static FormValidation checkOptionalIdentifier(Item item, String value, String fieldTitle) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }
            if (value.length() > MAX_FIELD_LENGTH) {
                return FormValidation.error(fieldTitle + " is too long");
            }
            if (StringUtils.containsAny(value, "\n", "\r", "\t")) {
                return FormValidation.error(fieldTitle + " must not contain line breaks");
            }
            return FormValidation.ok();
        }

        private static void checkConfigurePermission(Item item) {
            if (item == null) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            } else {
                item.checkPermission(Item.CONFIGURE);
            }
        }
    }
}
