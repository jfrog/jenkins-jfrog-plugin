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
    private String snapshotRepo;
    private String resolveRepo;
    private String resolveSnapshotRepo;
    private String resolverServerId;
    private boolean deployArtifacts = true;
    private boolean captureEnvVars;
    private String envVarsIncludePatterns;
    private String envVarsExcludePatterns;
    private String artifactIncludePatterns;
    private String artifactExcludePatterns;
    private boolean filterExcludedArtifactsFromBuild;
    private String deploymentProperties;
    private String project;
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

    public String getSnapshotRepo() {
        return snapshotRepo;
    }

    @DataBoundSetter
    public void setSnapshotRepo(String snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    public String getResolveRepo() {
        return resolveRepo;
    }

    @DataBoundSetter
    public void setResolveRepo(String resolveRepo) {
        this.resolveRepo = resolveRepo;
    }

    public String getResolveSnapshotRepo() {
        return resolveSnapshotRepo;
    }

    @DataBoundSetter
    public void setResolveSnapshotRepo(String resolveSnapshotRepo) {
        this.resolveSnapshotRepo = resolveSnapshotRepo;
    }

    public String getResolverServerId() {
        return resolverServerId;
    }

    @DataBoundSetter
    public void setResolverServerId(String resolverServerId) {
        this.resolverServerId = resolverServerId;
    }

    public boolean isDeployArtifacts() {
        return deployArtifacts;
    }

    @DataBoundSetter
    public void setDeployArtifacts(boolean deployArtifacts) {
        this.deployArtifacts = deployArtifacts;
    }

    public boolean isCaptureEnvVars() {
        return captureEnvVars;
    }

    @DataBoundSetter
    public void setCaptureEnvVars(boolean captureEnvVars) {
        this.captureEnvVars = captureEnvVars;
    }

    public String getEnvVarsIncludePatterns() {
        return envVarsIncludePatterns;
    }

    @DataBoundSetter
    public void setEnvVarsIncludePatterns(String envVarsIncludePatterns) {
        this.envVarsIncludePatterns = envVarsIncludePatterns;
    }

    public String getEnvVarsExcludePatterns() {
        return envVarsExcludePatterns;
    }

    @DataBoundSetter
    public void setEnvVarsExcludePatterns(String envVarsExcludePatterns) {
        this.envVarsExcludePatterns = envVarsExcludePatterns;
    }

    public String getArtifactIncludePatterns() {
        return artifactIncludePatterns;
    }

    @DataBoundSetter
    public void setArtifactIncludePatterns(String artifactIncludePatterns) {
        this.artifactIncludePatterns = artifactIncludePatterns;
    }

    public String getArtifactExcludePatterns() {
        return artifactExcludePatterns;
    }

    @DataBoundSetter
    public void setArtifactExcludePatterns(String artifactExcludePatterns) {
        this.artifactExcludePatterns = artifactExcludePatterns;
    }

    public boolean isFilterExcludedArtifactsFromBuild() {
        return filterExcludedArtifactsFromBuild;
    }

    @DataBoundSetter
    public void setFilterExcludedArtifactsFromBuild(boolean filterExcludedArtifactsFromBuild) {
        this.filterExcludedArtifactsFromBuild = filterExcludedArtifactsFromBuild;
    }

    public String getDeploymentProperties() {
        return deploymentProperties;
    }

    @DataBoundSetter
    public void setDeploymentProperties(String deploymentProperties) {
        this.deploymentProperties = deploymentProperties;
    }

    public String getProject() {
        return project;
    }

    @DataBoundSetter
    public void setProject(String project) {
        this.project = project;
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

    /**
     * The server to resolve dependencies from. Falls back to the deploy server (serverId) when
     * Resolver Server is left empty, so resolver and deployer share one server and its credentials
     * unless explicitly split.
     */
    public JFrogPlatformInstance resolveResolverServer() {
        String id = StringUtils.isNotBlank(resolverServerId) ? resolverServerId : serverId;
        return findServer(id);
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
            return "JFrog Artifactory (Maven Native Reporter)";
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
        public FormValidation doCheckResolveRepo(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return checkOptionalRepo(value);
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckSnapshotRepo(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return checkOptionalRepo(value);
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckResolveSnapshotRepo(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            return checkOptionalRepo(value);
        }

        private static FormValidation checkOptionalRepo(String value) {
            if (StringUtils.isBlank(value)) {
                // Optional: leaving it empty falls back to the corresponding release repo field
                // (or, for resolve fields left entirely empty, to Maven's own settings.xml).
                return FormValidation.ok();
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
        public ListBoxModel doFillResolverServerIdItems(@AncestorInPath Item item) {
            checkConfigurePermission(item);
            ListBoxModel items = new ListBoxModel();
            items.add("— Same as JFrog Platform Server above —", "");
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
        public FormValidation doCheckResolverServerId(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }
            if (findServer(value) == null) {
                return FormValidation.error("Unknown JFrog Platform server: " + value);
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckEnvVarsIncludePatterns(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "Include patterns");
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckEnvVarsExcludePatterns(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "Exclude patterns");
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckArtifactIncludePatterns(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "Artifact include patterns");
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckArtifactExcludePatterns(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "Artifact exclude patterns");
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckDeploymentProperties(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }
            if (value.length() > MAX_FIELD_LENGTH) {
                return FormValidation.error("Deployment properties is too long");
            }
            for (String pair : value.split(";")) {
                if (StringUtils.isBlank(pair)) {
                    continue;
                }
                if (!pair.contains("=") || StringUtils.isBlank(pair.substring(0, pair.indexOf('=')))) {
                    return FormValidation.error("Each entry must be key=value, separated by ';' (e.g. status=staging;region=us)");
                }
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckProject(@AncestorInPath Item item, @QueryParameter String value) {
            return checkOptionalIdentifier(item, value, "JFrog Project");
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
