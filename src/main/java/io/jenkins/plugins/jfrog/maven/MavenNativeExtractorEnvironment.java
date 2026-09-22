package io.jenkins.plugins.jfrog.maven;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.PlexusModuleContributor;
import hudson.maven.PlexusModuleContributorFactory;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.Node;
import hudson.remoting.Which;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.util.encryption.EncryptionKeyPair;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;

import static org.jfrog.build.api.BuildInfoConfigProperties.ENV_PROPERTIES_FILE_KEY;
import static org.jfrog.build.api.BuildInfoConfigProperties.ENV_PROPERTIES_FILE_KEY_IV;

/**
 * Sets up the environment a Maven Project build needs so that {@link BuildInfoRecorder} is
 * loaded into the forked Maven process and knows where to deploy artifacts.
 */
public class MavenNativeExtractorEnvironment extends Environment {

    private final AbstractBuild<?, ?> build;
    private final MavenArtifactoryReporter reporter;
    private final BuildListener listener;
    private FilePath propertiesFile;
    private String propertiesFileKey;
    private String propertiesFileKeyIv;
    private boolean tornDown;

    public MavenNativeExtractorEnvironment(AbstractBuild<?, ?> build, MavenArtifactoryReporter reporter,
                                           BuildListener listener) {
        this.build = build;
        this.reporter = reporter;
        this.listener = listener;
    }

    @Override
    public void buildEnvVars(Map<String, String> env) {
        if (tornDown) {
            // Environment.buildEnvVars() can be called again after tearDown() - e.g. a Freestyle-
            // style post-build action (like "Publish JFrog Build Info") added onto the same Maven
            // job calls build.getEnvironment() for its own purposes, which re-invokes every
            // registered Environment's buildEnvVars(). Maven itself already finished by then, so
            // there is nothing left for this env var to do - just skip it rather than recreate a
            // properties file nothing will read.
            return;
        }

        if (build instanceof MavenModuleSetBuild) {
            String goals = ((MavenModuleSetBuild) build).getProject().getGoals();
            if (!MavenGoals.allowsArtifactoryPublish(goals)) {
                throw new IllegalStateException("[JFrog] Maven native capture requires Goals to include " +
                        "'install' or 'deploy'. The extractor skips deploy and build-info for other goals " +
                        "(for example 'clean package'). Current goals: '" +
                        StringUtils.defaultString(goals) + "'.");
            }
        }

        JFrogPlatformInstance server = reporter.resolveServer();
        if (server == null) {
            throw new IllegalStateException("[JFrog] Maven native capture: server ID '" + reporter.getServerId() +
                    "' is not configured under Manage Jenkins -> System -> JFrog Platform.");
        }

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            // Called again later in the build lifecycle once the workspace exists.
            return;
        }

        try {
            if (propertiesFile == null) {
                // Must NOT call build.getEnvironment(listener) here — that re-invokes every
                // Environment.buildEnvVars(), including this one, causing unbounded recursion.
                ArtifactoryClientConfiguration configuration = buildClientConfiguration(server, new EnvVars(env));
                propertiesFile = workspace.createTextTempFile("jfrog-buildinfo", ".properties", "");
                configuration.setPropertiesFile(propertiesFile.getRemote());
                try (OutputStream out = propertiesFile.write()) {
                    EncryptionKeyPair keyPair = configuration.persistToEncryptedPropertiesFile(out);
                    if (keyPair != null) {
                        propertiesFileKey = keyPair.getStringSecretKey();
                        propertiesFileKeyIv = keyPair.getStringIv();
                    }
                }
                listener.getLogger().println("[JFrog] Maven native capture enabled (server: " + server.getId() +
                        ", repo: " + configuration.publisher.getRepoKey() +
                        ", deployArtifacts: " + reporter.isDeployArtifacts() + ")");
            }
            String propsPath = propertiesFile.getRemote();
            env.put(BuildInfoConfigProperties.PROP_PROPS_FILE, propsPath);
            // The extractor reads BUILDINFO_PROPFILE. Dotted keys like
            // buildInfoConfig.propertiesFile are often dropped by the forked Maven JVM.
            env.put(BuildInfoConfigProperties.ENV_BUILDINFO_PROPFILE, propsPath);
            if (StringUtils.isNotBlank(propertiesFileKey) && StringUtils.isNotBlank(propertiesFileKeyIv)) {
                env.put(ENV_PROPERTIES_FILE_KEY, propertiesFileKey);
                env.put(ENV_PROPERTIES_FILE_KEY_IV, propertiesFileKeyIv);
            }
        } catch (RuntimeException e) {
            deletePropertiesFileQuietly();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deletePropertiesFileQuietly();
            throw new IllegalStateException("[JFrog] Failed to set up Maven native capture: " + e.getMessage(), e);
        } catch (Exception e) {
            deletePropertiesFileQuietly();
            throw new IllegalStateException("[JFrog] Failed to set up Maven native capture: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean tearDown(AbstractBuild build, BuildListener listener) {
        tornDown = true;
        deletePropertiesFileQuietly();
        return true;
    }

    private ArtifactoryClientConfiguration buildClientConfiguration(JFrogPlatformInstance server, EnvVars env)
            throws IOException {
        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(new NullLog());

        CredentialsConfig credentialsConfig = server.getCredentialsConfig();
        if (credentialsConfig == null || StringUtils.isBlank(credentialsConfig.getCredentialsId())) {
            throw new IllegalStateException("[JFrog] Maven native capture: server '" + server.getId() +
                    "' has no credentials configured.");
        }
        Credentials credentials = PluginsUtils.credentialsLookup(credentialsConfig.getCredentialsId(), build.getParent());
        if (credentials == null || credentials == Credentials.EMPTY_CREDENTIALS) {
            throw new IllegalStateException("[JFrog] Maven native capture: credentials id '" +
                    credentialsConfig.getCredentialsId() + "' could not be resolved.");
        }

        configuration.publisher.setContextUrl(server.inferArtifactoryUrl());
        String repo = env.expand(StringUtils.defaultString(reporter.getArtifactoryRepo()));
        if (StringUtils.isBlank(repo)) {
            throw new IllegalStateException("[JFrog] Maven native capture: Artifactory repository is empty.");
        }
        configuration.publisher.setRepoKey(repo);
        configuration.publisher.setSnapshotRepoKey(repo);
        configuration.publisher.setMaven(true);
        configuration.publisher.setPublishArtifacts(reporter.isDeployArtifacts());
        configuration.publisher.setPublishBuildInfo(Boolean.TRUE);
        applyCredentials(configuration, credentials);
        applyProxy(configuration, server.inferArtifactoryUrl());

        configuration.info.setBuildName(MavenBuildIdentifiers.resolveBuildName(
                reporter.getBuildName(), env, build.getParent().getFullName()));
        configuration.info.setBuildNumber(MavenBuildIdentifiers.resolveBuildNumber(
                reporter.getBuildNumber(), env, String.valueOf(build.getNumber())));
        String buildUrl = MavenBuildIdentifiers.resolveBuildUrl(env);
        if (StringUtils.isNotBlank(buildUrl)) {
            configuration.info.setBuildUrl(buildUrl);
        }
        configuration.setActivateRecorder(Boolean.TRUE);
        return configuration;
    }

    private static void applyCredentials(ArtifactoryClientConfiguration configuration, Credentials credentials) {
        if (StringUtils.isNotBlank(credentials.getPlainTextAccessToken())) {
            String token = credentials.getPlainTextAccessToken();
            configuration.publisher.setUsername(MavenAccessTokens.usernameOrEmpty(token));
            configuration.publisher.setPassword(token);
            return;
        }
        if (StringUtils.isBlank(credentials.getPlainTextUsername())
                && StringUtils.isBlank(credentials.getPlainTextPassword())) {
            throw new IllegalStateException("[JFrog] Maven native capture: resolved credentials are empty.");
        }
        configuration.publisher.setUsername(credentials.getPlainTextUsername());
        configuration.publisher.setPassword(credentials.getPlainTextPassword());
    }

    private static void applyProxy(ArtifactoryClientConfiguration configuration, String artifactoryUrl) {
        JenkinsProxyConfiguration proxy = new JenkinsProxyConfiguration();
        if (!proxy.isProxyConfigured(artifactoryUrl)) {
            return;
        }
        configuration.proxy.setHost(proxy.host);
        configuration.proxy.setPort(proxy.port);
        if (StringUtils.isNotBlank(proxy.username)) {
            configuration.proxy.setUsername(proxy.username);
        }
        if (StringUtils.isNotBlank(proxy.password)) {
            configuration.proxy.setPassword(proxy.password);
        }
        if (StringUtils.isNotBlank(proxy.noProxy)) {
            configuration.proxy.setNoProxy(proxy.noProxy);
        }
    }

    private void deletePropertiesFileQuietly() {
        if (propertiesFile == null) {
            return;
        }
        try {
            if (propertiesFile.exists()) {
                propertiesFile.delete();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup of the encrypted properties file.
        }
        propertiesFile = null;
    }

    /**
     * Contributes the filtered {@code build-info-extractor-maven3} jar into the forked Maven
     * process's "plexus.core" bootstrap realm. Skipped when Maven Integration is not installed.
     */
    @hudson.Extension(optional = true)
    public static class ArtifactoryPlexusContributor extends PlexusModuleContributorFactory {

        @Override
        public PlexusModuleContributor createFor(AbstractBuild<?, ?> context) throws IOException, InterruptedException {
            if (MavenNativeExtractorListener.findReporter(context) == null) {
                return null;
            }
            Node node = context.getBuiltOn();
            if (node == null || node.getRootPath() == null) {
                throw new IOException("Cannot contribute JFrog Maven extractor: build node is not available");
            }
            FilePath[] files = PluginDependencyHelper.getActualDependencyDirectory(
                    Which.jarFile(BuildInfoRecorder.class), node.getRootPath())
                    .list("*.jar", "classes.jar");
            return PlexusModuleContributor.of(Arrays.asList(files));
        }
    }
}
