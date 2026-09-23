package io.jenkins.plugins.jfrog.maven;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.PlexusModuleContributor;
import hudson.maven.PlexusModuleContributorFactory;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Which;
import hudson.tasks.Maven;
import io.jenkins.plugins.jfrog.CliEnvConfigurator;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.FolderCredentialsResolver;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import hudson.util.Secret;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.util.encryption.EncryptionKeyPair;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            // Hand the path directly to MavenExtractorArguments so its intercept() doesn't need
            // to call build.getEnvironment() (which would re-invoke this method) just to read it.
            MavenNativeExtractorListener.MavenExtractorArguments extractorArguments =
                    build.getAction(MavenNativeExtractorListener.MavenExtractorArguments.class);
            if (extractorArguments != null) {
                extractorArguments.setPropsPath(propsPath);
            }
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

        Credentials credentials = resolveCredentials(server, "Maven native capture");
        configuration.publisher.setContextUrl(server.inferArtifactoryUrl());
        String releaseRepo = env.expand(StringUtils.defaultString(reporter.getArtifactoryRepo()));
        if (StringUtils.isBlank(releaseRepo)) {
            throw new IllegalStateException("[JFrog] Maven native capture: Artifactory repository is empty.");
        }
        String snapshotRepo = env.expand(StringUtils.defaultString(reporter.getSnapshotRepo()));
        if (StringUtils.isBlank(snapshotRepo)) {
            snapshotRepo = releaseRepo;
        }
        configuration.publisher.setRepoKey(releaseRepo);
        configuration.publisher.setSnapshotRepoKey(snapshotRepo);
        configuration.publisher.setMaven(true);
        configuration.publisher.setPublishArtifacts(reporter.isDeployArtifacts());
        configuration.publisher.setPublishBuildInfo(Boolean.TRUE);
        applyCredentials(configuration.publisher, credentials);
        applyProxy(configuration, server.inferArtifactoryUrl());

        String artifactIncludePatterns = env.expand(StringUtils.defaultString(reporter.getArtifactIncludePatterns()));
        if (StringUtils.isNotBlank(artifactIncludePatterns)) {
            configuration.publisher.setIncludePatterns(artifactIncludePatterns);
        }
        String artifactExcludePatterns = env.expand(StringUtils.defaultString(reporter.getArtifactExcludePatterns()));
        if (StringUtils.isNotBlank(artifactExcludePatterns)) {
            configuration.publisher.setExcludePatterns(artifactExcludePatterns);
        }
        configuration.publisher.setFilterExcludedArtifactsFromBuild(reporter.isFilterExcludedArtifactsFromBuild());

        String deploymentProperties = env.expand(StringUtils.defaultString(reporter.getDeploymentProperties()));
        if (StringUtils.isNotBlank(deploymentProperties)) {
            configuration.publisher.addMatrixParams(parseDeploymentProperties(deploymentProperties));
        }

        // Resolver is entirely independent of the publisher above: it is only activated when a
        // Resolve Repository is explicitly configured, and never falls back to the deploy repo.
        // It may also use a different JFrog Platform Server (and therefore different credentials)
        // than the deployer, via Resolver Server; when left blank, it shares the deploy server.
        String resolveRepo = env.expand(StringUtils.defaultString(reporter.getResolveRepo()));
        if (StringUtils.isNotBlank(resolveRepo)) {
            checkMavenVersionSupportsResolution(env);
            JFrogPlatformInstance resolverServer = reporter.resolveResolverServer();
            if (resolverServer == null) {
                throw new IllegalStateException("[JFrog] Maven native capture: resolver server ID '" +
                        StringUtils.defaultIfBlank(reporter.getResolverServerId(), reporter.getServerId()) +
                        "' is not configured under Manage Jenkins -> System -> JFrog Platform.");
            }
            Credentials resolverCredentials = resolveCredentials(resolverServer, "Maven native capture (resolver)");
            String resolveSnapshotRepo = env.expand(StringUtils.defaultString(reporter.getResolveSnapshotRepo()));
            if (StringUtils.isBlank(resolveSnapshotRepo)) {
                resolveSnapshotRepo = resolveRepo;
            }
            configuration.resolver.setContextUrl(resolverServer.inferArtifactoryUrl());
            configuration.resolver.setRepoKey(resolveRepo);
            configuration.resolver.setDownloadSnapshotRepoKey(resolveSnapshotRepo);
            configuration.resolver.setMaven(true);
            applyCredentials(configuration.resolver, resolverCredentials);
            listener.getLogger().println("[JFrog] Maven native capture: resolving dependencies from '" +
                    resolveRepo + "' (snapshots: '" + resolveSnapshotRepo + "') on server '" +
                    resolverServer.getId() + "'.");
        }

        if (reporter.isCaptureEnvVars()) {
            configuration.setIncludeEnvVars(Boolean.TRUE);
            String includePatterns = env.expand(StringUtils.defaultString(reporter.getEnvVarsIncludePatterns()));
            configuration.setEnvVarsIncludePatterns(StringUtils.isNotBlank(includePatterns) ? includePatterns : "*");
            String excludePatterns = env.expand(StringUtils.defaultString(reporter.getEnvVarsExcludePatterns()));
            configuration.setEnvVarsExcludePatterns(StringUtils.isNotBlank(excludePatterns) ? excludePatterns :
                    "*password*;*psw*;*secret*;*key*;*token*;*auth*");
        }

        configuration.info.setBuildName(MavenBuildIdentifiers.resolveBuildName(
                reporter.getBuildName(), env, build.getParent().getFullName()));
        configuration.info.setBuildNumber(MavenBuildIdentifiers.resolveBuildNumber(
                reporter.getBuildNumber(), env, String.valueOf(build.getNumber())));
        String buildUrl = MavenBuildIdentifiers.resolveBuildUrl(env);
        if (StringUtils.isNotBlank(buildUrl)) {
            configuration.info.setBuildUrl(buildUrl);
        }
        String project = env.expand(StringUtils.defaultString(reporter.getProject()));
        if (StringUtils.isNotBlank(project)) {
            configuration.info.setProject(project);
        }
        configuration.setActivateRecorder(Boolean.TRUE);
        return configuration;
    }

    /**
     * Parses a semicolon-separated {@code key=value} deployment properties string into a map,
     * e.g. {@code status=staging;region=us}. Blank entries are skipped.
     */
    static Map<String, String> parseDeploymentProperties(String deploymentProperties) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : deploymentProperties.split(";")) {
            if (StringUtils.isBlank(pair)) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            params.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
        }
        return params;
    }

    /**
     * Dependency resolution from Artifactory (via {@code ArtifactoryEclipseArtifactResolver} et al.
     * in build-info-extractor-maven3) requires Maven 3.0.2+. Older versions silently ignore the
     * resolver override, which would otherwise look like resolution "worked" but used Central instead.
     * Maven 3.9.12+ is rejected when Resolve is configured: the extractor's PluginManager override
     * NPEs on those releases (build-info#841) until an upstream fix ships.
     * Best-effort: if the Maven installation's version cannot be determined, the build is allowed to
     * proceed rather than fail on an unrelated detection problem.
     */
    private void checkMavenVersionSupportsResolution(EnvVars env) {
        String version = detectMavenVersion(build, env, listener);
        if (version == null) {
            // Best-effort detection only; do not fail the build over an unrelated I/O problem.
            return;
        }
        assertMavenVersionSupportsResolution(version);
    }

    /**
     * Reads the Maven version a build will run with, from its {@code maven-core-<version>.jar}.
     * Returns {@code null} when the version cannot be determined (non-Maven-Project build, no
     * explicit tool, missing home, etc.) rather than throwing - callers decide what "unknown"
     * means for their own safe-default behavior.
     * <p>
     * Must NOT be called with an env obtained via {@code build.getEnvironment(listener)} from
     * inside {@link #buildEnvVars(Map)} itself — that re-invokes every registered
     * {@code Environment.buildEnvVars()}, including this one, causing unbounded recursion. Pass
     * the {@link EnvVars} already being assembled by the in-progress call instead. Callers outside
     * that call stack (e.g. {@link ArtifactoryPlexusContributor}, which runs later when the forked
     * Maven process is actually launched) may safely resolve their own {@link EnvVars} first.
     */
    static String detectMavenVersion(AbstractBuild<?, ?> build, EnvVars env, TaskListener listener) {
        if (!(build instanceof MavenModuleSetBuild)) {
            return null;
        }
        try {
            MavenModuleSet project = ((MavenModuleSetBuild) build).getProject();
            Maven.MavenInstallation installation = project.getMaven();
            if (installation == null) {
                return null;
            }
            Node node = build.getBuiltOn();
            if (node != null) {
                installation = installation.forNode(node, listener);
            }
            installation = installation.forEnvironment(env);
            String home = installation.getHome();
            if (StringUtils.isBlank(home)) {
                return null;
            }
            FilePath homePath = new FilePath(build.getWorkspace().getChannel(), home);
            FilePath[] coreJars = homePath.child("lib").list("maven-core-*.jar");
            if (coreJars == null || coreJars.length == 0) {
                return null;
            }
            Matcher matcher = Pattern.compile("maven-core-(.+)\\.jar").matcher(coreJars[0].getName());
            return matcher.matches() ? matcher.group(1) : null;
        } catch (Exception e) {
            listener.getLogger().println("[JFrog] Maven native capture: could not determine Maven version (" +
                    e.getMessage() + "); skipping the minimum-version check for resolution.");
            return null;
        }
    }

    /**
     * Visible for tests. Rejects Maven &lt; 3.0.2 (silent Central fallback) and Maven &gt;= 3.9.12
     * (extractor PluginManager NPE) when Resolve Repository is configured.
     */
    static void assertMavenVersionSupportsResolution(String version) {
        ComparableVersion found = new ComparableVersion(version);
        ComparableVersion minimum = new ComparableVersion("3.0.2");
        if (found.compareTo(minimum) < 0) {
            throw new IllegalStateException("[JFrog] Maven native capture: resolving dependencies from " +
                    "Artifactory requires Maven 3.0.2 or higher. Detected: " + version + ".");
        }
        ComparableVersion brokenFrom = new ComparableVersion("3.9.12");
        if (found.compareTo(brokenFrom) >= 0) {
            throw new IllegalStateException("[JFrog] Maven native capture: resolving dependencies from " +
                    "Artifactory is not compatible with Maven " + version +
                    " (extractor PluginManager NPE on 3.9.12+; see jfrog/build-info#841). " +
                    "Use Maven 3.8.x or 3.9.0–3.9.11, or leave Resolve Repository empty and use settings.xml.");
        }
    }

    /**
     * Non-throwing variant of {@link #assertMavenVersionSupportsResolution(String)} for callers
     * that need a boolean to pick a code path rather than fail the build.
     */
    static boolean supportsResolution(String version) {
        try {
            assertMavenVersionSupportsResolution(version);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Credentials resolveCredentials(JFrogPlatformInstance server, String context) {
        CredentialsConfig credentialsConfig = server.getCredentialsConfig();
        if (credentialsConfig == null || StringUtils.isBlank(credentialsConfig.getCredentialsId())) {
            throw new IllegalStateException("[JFrog] " + context + ": server '" + server.getId() +
                    "' has no credentials configured.");
        }
        String credentialsId = credentialsConfig.getCredentialsId();
        Item lookupContext = build.getParent();

        // Prefer a folder-scoped override for this server ID when the job lives under a folder
        // that maps the server to different credentials - matches the Pipeline/Freestyle CLI path
        // in JfStep.addCredentialsArguments(), so Maven-native builds resolve credentials the same
        // way as every other job type in the same folder.
        FolderCredentialsResolver.Resolution folderOverride =
                FolderCredentialsResolver.resolve(build.getParent(), server.getId());
        if (folderOverride != null) {
            credentialsId = folderOverride.getCredentialsId();
            lookupContext = folderOverride.getContext();
        }

        StringCredentials accessTokenCredentials = PluginsUtils.accessTokenCredentialsLookup(credentialsId, lookupContext);
        if (accessTokenCredentials != null) {
            return new Credentials(Secret.fromString(""), Secret.fromString(""),
                    accessTokenCredentials.getSecret());
        }

        Credentials credentials = PluginsUtils.credentialsLookup(credentialsId, lookupContext);
        if (credentials == null || credentials == Credentials.EMPTY_CREDENTIALS) {
            if (folderOverride != null) {
                throw new IllegalStateException("[JFrog] " + context + ": folder-level credentials override for " +
                        "server '" + server.getId() + "' in folder '" + folderOverride.getContext().getFullName() +
                        "' resolved to no credentials (id '" + credentialsId + "'). The credential may have been " +
                        "deleted or renamed.");
            }
            throw new IllegalStateException("[JFrog] " + context + ": credentials id '" +
                    credentialsId + "' could not be resolved.");
        }
        return credentials;
    }

    private static void applyCredentials(ArtifactoryClientConfiguration.RepositoryConfiguration handler,
                                          Credentials credentials) {
        if (StringUtils.isNotBlank(credentials.getPlainTextAccessToken())) {
            String token = credentials.getPlainTextAccessToken();
            handler.setUsername(MavenAccessTokens.usernameOrEmpty(token));
            handler.setPassword(token);
            return;
        }
        if (StringUtils.isBlank(credentials.getPlainTextUsername())
                && StringUtils.isBlank(credentials.getPlainTextPassword())) {
            throw new IllegalStateException("[JFrog] Maven native capture: resolved credentials are empty.");
        }
        handler.setUsername(credentials.getPlainTextUsername());
        handler.setPassword(credentials.getPlainTextPassword());
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
            // Same wildcard-to-suffix normalization CliEnvConfigurator applies for the CLI path,
            // so curl/npm-style NO_PROXY matching behaves identically for Maven-native builds.
            configuration.proxy.setNoProxy(CliEnvConfigurator.createNoProxyValue(proxy.noProxy));
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
     * Contributes the {@code build-info-extractor-maven3} jar into the forked Maven process's
     * "plexus.core" bootstrap realm. Skipped when Maven Integration is not installed.
     * <p>
     * Two variants are staged by the vendor script: a full jar (BuildInfoRecorder + the
     * Artifactory-backed resolver/ArtifactoryProjectBuilder Plexus overrides) and a no-resolver
     * jar (BuildInfoRecorder only). The resolver overrides NPE on Maven 3.9.12+ as soon as they
     * are on the classpath at all (jfrog/build-info#841) - independent of whether Resolve
     * Repository is actually configured. The full jar is therefore only selected when resolution
     * is both requested and known to run on a compatible Maven version; every other build gets
     * the no-resolver jar, so plain deploy/build-info capture is never at risk from that bug.
     */
    @hudson.Extension(optional = true)
    public static class ArtifactoryPlexusContributor extends PlexusModuleContributorFactory {

        @Override
        public PlexusModuleContributor createFor(AbstractBuild<?, ?> context) throws IOException, InterruptedException {
            MavenArtifactoryReporter reporter = MavenNativeExtractorListener.findReporter(context);
            if (reporter == null) {
                return null;
            }
            Node node = context.getBuiltOn();
            if (node == null || node.getRootPath() == null) {
                throw new IOException("Cannot contribute JFrog Maven extractor: build node is not available");
            }
            boolean useFullJar = false;
            if (StringUtils.isNotBlank(reporter.getResolveRepo())) {
                EnvVars env;
                try {
                    env = context.getEnvironment(TaskListener.NULL);
                } catch (Exception e) {
                    env = new EnvVars();
                }
                String version = detectMavenVersion(context, env, TaskListener.NULL);
                useFullJar = version != null && supportsResolution(version);
            }

            FilePath dependencyDir = PluginDependencyHelper.getActualDependencyDirectory(
                    Which.jarFile(BuildInfoRecorder.class), node.getRootPath());
            FilePath[] allFiles = dependencyDir.list("*.jar", "classes.jar");
            List<FilePath> selected = new ArrayList<>();
            for (FilePath file : allFiles) {
                String name = file.getName();
                boolean isFullExtractorJar = name.startsWith("build-info-extractor-maven3-")
                        && !name.endsWith("-no-resolver.jar");
                boolean isNoResolverExtractorJar = name.endsWith("-no-resolver.jar");
                if (isFullExtractorJar && !useFullJar) {
                    continue;
                }
                if (isNoResolverExtractorJar && useFullJar) {
                    continue;
                }
                selected.add(file);
            }
            return PlexusModuleContributor.of(selected);
        }
    }
}
