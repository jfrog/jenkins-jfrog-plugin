package io.jenkins.plugins.jfrog.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.model.Item;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Maven Project Build Settings config for native Artifactory publish.
 * Capture/deploy runs in Maven's JVM via {@link MavenNativeExtractorEnvironment}.
 */
public class MavenArtifactoryReporter extends MavenReporter {

    private static final int MAX_FIELD_LENGTH = 200;
    private static final String REPO_PATTERN = "^[\\w$][\\w./${}-]*$";

    private String serverId;
    private String releaseRepo;
    private String snapshotRepo;
    private String resolveRepo;
    private String resolveSnapshotRepo;
    private String resolveServerId;
    private boolean deployArtifacts = true;
    private boolean captureEnvVars;
    private String envVarsIncludePatterns;
    private String envVarsExcludePatterns;
    private String artifactIncludePatterns;
    private String artifactExcludePatterns;
    private String deploymentProperties;
    private String project;
    private String buildName;
    private String buildNumber;
    private boolean captureVcs;
    private boolean publishBuildInfo = true;

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

    public String getReleaseRepo() {
        return releaseRepo;
    }

    @DataBoundSetter
    public void setReleaseRepo(String releaseRepo) {
        this.releaseRepo = releaseRepo;
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

    public String getResolveServerId() {
        return resolveServerId;
    }

    @DataBoundSetter
    public void setResolveServerId(String resolveServerId) {
        this.resolveServerId = resolveServerId;
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

    public boolean isCaptureVcs() {
        return captureVcs;
    }

    @DataBoundSetter
    public void setCaptureVcs(boolean captureVcs) {
        this.captureVcs = captureVcs;
    }

    public boolean isPublishBuildInfo() {
        return publishBuildInfo;
    }

    @DataBoundSetter
    public void setPublishBuildInfo(boolean publishBuildInfo) {
        this.publishBuildInfo = publishBuildInfo;
    }

    /**
     * The server to deploy to and publish build info to.
     */
    public JFrogPlatformInstance findDeployServer() {
        return findServer(serverId);
    }

    /**
     * The server to resolve dependencies from. Independent of the deploy server (serverId) - no
     * fallback; JFrog Resolve Server must be explicitly selected whenever Resolve Repository is
     * used.
     */
    public JFrogPlatformInstance findResolveServer() {
        return findServer(resolveServerId);
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
        public ComboBoxModel doFillReleaseRepoItems(@AncestorInPath Item item, @QueryParameter String serverId) {
            checkConfigurePermission(item);
            return listRepoKeys(item, serverId, "local");
        }

        @POST
        @SuppressWarnings("unused")
        public ComboBoxModel doFillSnapshotRepoItems(@AncestorInPath Item item, @QueryParameter String serverId) {
            checkConfigurePermission(item);
            return listRepoKeys(item, serverId, "local");
        }

        @POST
        @SuppressWarnings("unused")
        public ComboBoxModel doFillResolveRepoItems(@AncestorInPath Item item, @QueryParameter String resolveServerId) {
            checkConfigurePermission(item);
            return listRepoKeys(item, resolveServerId, "virtual");
        }

        @POST
        @SuppressWarnings("unused")
        public ComboBoxModel doFillResolveSnapshotRepoItems(@AncestorInPath Item item,
                                                             @QueryParameter String resolveServerId) {
            checkConfigurePermission(item);
            return listRepoKeys(item, resolveServerId, "virtual");
        }

        /**
         * Lists Maven-package-type repository keys of the given {@code repoType} ("local" for
         * deploy targets, "virtual" for resolution) from the given server, for the repo combo
         * boxes above. Best-effort: an empty list on any failure (server not yet chosen or
         * unresolvable, credentials not readable, network error) just leaves the combo box with no
         * suggestions - {@code f:combobox} (unlike a strict dropdown) still accepts free typing, so
         * users are never blocked from entering a repo key manually.
         */
        private static ComboBoxModel listRepoKeys(Item item, String serverId, String repoType) {
            ComboBoxModel keys = new ComboBoxModel();
            JFrogPlatformInstance server = findServer(serverId);
            if (server == null) {
                return keys;
            }
            try {
                CredentialsConfig credentialsConfig = server.getCredentialsConfig();
                if (credentialsConfig == null || StringUtils.isBlank(credentialsConfig.getCredentialsId())) {
                    return keys;
                }
                String credentialsId = credentialsConfig.getCredentialsId();
                String apiUrl = StringUtils.removeEnd(server.inferArtifactoryUrl(), "/") +
                        "/api/repositories?type=" + repoType;
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(apiUrl)).GET();
                StringCredentials accessTokenCredentials = PluginsUtils.accessTokenCredentialsLookup(credentialsId, item);
                if (accessTokenCredentials != null) {
                    requestBuilder.header("Authorization", "Bearer " + accessTokenCredentials.getSecret().getPlainText());
                } else {
                    Credentials credentials = PluginsUtils.credentialsLookup(credentialsId, item);
                    if (credentials == null) {
                        return keys;
                    }
                    String basicAuth = Base64.getEncoder().encodeToString((credentials.getPlainTextUsername() +
                            ":" + credentials.getPlainTextPassword()).getBytes(StandardCharsets.UTF_8));
                    requestBuilder.header("Authorization", "Basic " + basicAuth);
                }
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return keys;
                }
                JsonNode root = new ObjectMapper().readTree(response.body());
                List<String> mavenRepoKeys = new ArrayList<>();
                for (JsonNode repo : root) {
                    if ("maven".equalsIgnoreCase(repo.path("packageType").asText(""))) {
                        mavenRepoKeys.add(repo.path("key").asText());
                    }
                }
                keys.addAll(mavenRepoKeys);
            } catch (Exception ignored) {
                // Best-effort only.
            }
            return keys;
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckServerId(@AncestorInPath Item item, @QueryParameter String value,
                                               @QueryParameter boolean deployArtifacts,
                                               @QueryParameter boolean publishBuildInfo) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                // Required whenever Deploy Artifacts or Capture and publish build info is checked -
                // both need somewhere to deploy/publish to. Only optional when neither is checked
                // (resolution-only use, matching the legacy plugin's posture of the deploy publisher
                // being an entirely separate, optional block from the resolver wrapper). Deliberately
                // NOT keyed off Resolve Repository (a different, earlier section) - keeping this
                // check scoped to same-section sibling fields keeps live re-validation reliable.
                if (deployArtifacts || publishBuildInfo) {
                    List<JFrogPlatformInstance> instances = JFrogPlatformBuilder.getJFrogPlatformInstances();
                    if (instances == null || instances.isEmpty()) {
                        return FormValidation.error("No JFrog Platform instances configured. Add one under Manage Jenkins → System.");
                    }
                    return FormValidation.error("Select a server - required when Deploy Artifacts or " +
                            "Capture and publish build info is enabled.");
                }
                return FormValidation.ok();
            }
            if (findServer(value) == null) {
                return FormValidation.error("Unknown JFrog Platform server: " + value);
            }
            return FormValidation.ok();
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckReleaseRepo(@AncestorInPath Item item, @QueryParameter String value,
                                                  @QueryParameter boolean deployArtifacts) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                return deployArtifacts
                        ? FormValidation.error("Repository must not be empty when Deploy Artifacts is enabled")
                        : FormValidation.ok();
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
            if (StringUtils.isBlank(value)) {
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
        public ListBoxModel doFillResolveServerIdItems(@AncestorInPath Item item) {
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
        public FormValidation doCheckResolveServerId(@AncestorInPath Item item, @QueryParameter String value,
                                                      @QueryParameter String resolveRepo) {
            checkConfigurePermission(item);
            if (StringUtils.isBlank(value)) {
                // Required only when Resolve Repository has a value - i.e., when resolution is in use.
                // No fallback to JFrog Artifactory Server; the two are entirely independent.
                if (StringUtils.isNotBlank(resolveRepo)) {
                    return FormValidation.error("JFrog Resolve Server must be selected when resolving dependencies.");
                }
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
