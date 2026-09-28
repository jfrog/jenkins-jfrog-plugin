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
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.tasks.Maven;
import io.jenkins.plugins.jfrog.CliEnvConfigurator;
import io.jenkins.plugins.jfrog.actions.BuildInfoBuildBadgeAction;
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
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.util.encryption.EncryptionKeyPair;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jfrog.build.api.BuildInfoConfigProperties.ENV_PROPERTIES_FILE_KEY;
import static org.jfrog.build.api.BuildInfoConfigProperties.ENV_PROPERTIES_FILE_KEY_IV;
import static org.jfrog.build.extractor.clientConfiguration.ClientConfigurationFields.ADD_DEPLOYABLE_ARTIFACTS;

/**
 * Sets up the environment a Maven Project build needs so that the Maven extractor
 * is loaded into the forked Maven process.
 */
public class MavenNativeExtractorEnvironment extends Environment {

    private final AbstractBuild<?, ?> build;
    private final MavenArtifactoryReporter reporter;
    private final BuildListener listener;
    private FilePath propertiesFile;
    private String propertiesFileKey;
    private String propertiesFileKeyIv;
    private boolean tornDown;
    private JFrogPlatformInstance resolvedServer;

    public MavenNativeExtractorEnvironment(AbstractBuild<?, ?> build, MavenArtifactoryReporter reporter,
                                           BuildListener listener) {
        this.build = build;
        this.reporter = reporter;
        this.listener = listener;
    }

    @Override
    public void buildEnvVars(Map<String, String> env) {
        if (tornDown) {
            // Post-build actions call build.getEnvironment() after Maven has finished, which
            // re-invokes this method. Skip rather than recreate a properties file nothing will read.
            return;
        }

        if (StringUtils.isBlank(reporter.getResolveRepo()) || reporter.findResolveServer() == null) {
            throw fail("JFrog Resolve Server and Resolve Repository are required.");
        }

        // Goals only matter when deploying or publishing; resolution-only jobs can use any goals.
        boolean intendsToPublish = reporter.shouldConfigurePublisher();
        if (intendsToPublish && build instanceof MavenModuleSetBuild) {
            String goals = ((MavenModuleSetBuild) build).getProject().getGoals();
            if (!MavenGoals.allowsArtifactoryPublish(goals)) {
                throw fail("requires Goals to include 'install' or 'deploy'. The extractor skips " +
                        "deploy and build-info for other goals (for example 'clean package'). Current goals: '" +
                        StringUtils.defaultString(goals) + "'.");
            }
        }

        JFrogPlatformInstance server = intendsToPublish ? reporter.findDeployServer() : null;
        if (intendsToPublish && server == null) {
            throw fail("server ID '" + reporter.getServerId() +
                    "' is not configured under Manage Jenkins -> System -> JFrog Platform.");
        }

        FilePath workspace = build.getWorkspace();
        FilePath nodeRoot = null;
        Node node = build.getBuiltOn();
        if (node != null) {
            nodeRoot = node.getRootPath();
        }
        FilePath propertiesDir = propertiesDirectory(workspace, nodeRoot);
        if (propertiesDir == null) {
            // Called again later in the build lifecycle once the node or workspace exists.
            return;
        }

        try {
            if (propertiesFile != null && !propertiesFile.exists()) {
                // First-build SCM checkout can wipe a file written during earlier env setup.
                propertiesFile = null;
                propertiesFileKey = null;
                propertiesFileKeyIv = null;
            }
            if (propertiesFile == null) {
                // Must not call build.getEnvironment(listener) here — that re-invokes every
                // Environment.buildEnvVars(), including this one, and recurses until the stack overflows.
                ArtifactoryClientConfiguration configuration = buildClientConfiguration(server, new EnvVars(env));
                propertiesFile = propertiesDir.createTextTempFile("jfrog-buildinfo", ".properties", "");
                configuration.setPropertiesFile(propertiesFile.getRemote());
                try (OutputStream out = propertiesFile.write()) {
                    EncryptionKeyPair keyPair = configuration.persistToEncryptedPropertiesFile(out);
                    if (keyPair != null) {
                        propertiesFileKey = keyPair.getStringSecretKey();
                        propertiesFileKeyIv = keyPair.getStringIv();
                    }
                }
                listener.getLogger().println("[JFrog] Maven native capture enabled (server: " +
                        (server != null ? server.getId() : "none (resolution-only)") +
                        ", repo: " + StringUtils.defaultIfBlank(configuration.publisher.getRepoKey(), "n/a") +
                        ", deployArtifacts: " + reporter.isDeployArtifacts() + ")");
            }
            String propsPath = propertiesFile.getRemote();
            env.put(BuildInfoConfigProperties.PROP_PROPS_FILE, propsPath);
            // Forked Maven often drops dotted keys like buildInfoConfig.propertiesFile.
            env.put(BuildInfoConfigProperties.ENV_BUILDINFO_PROPFILE, propsPath);
            MavenNativeExtractorListener.MavenExtractorArguments extractorArguments =
                    build.getAction(MavenNativeExtractorListener.MavenExtractorArguments.class);
            if (extractorArguments != null) {
                extractorArguments.setPropsPath(propsPath);
            }
            if (StringUtils.isNotBlank(propertiesFileKey) && StringUtils.isNotBlank(propertiesFileKeyIv)) {
                // Encryption key/IV stay in the environment, never on the Executing Maven: log line.
                env.put(ENV_PROPERTIES_FILE_KEY, propertiesFileKey);
                env.put(ENV_PROPERTIES_FILE_KEY_IV, propertiesFileKeyIv);
            }
        } catch (RuntimeException e) {
            deletePropertiesFileQuietly();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deletePropertiesFileQuietly();
            throw fail("Failed to set up Maven native capture: " + e.getMessage(), e);
        } catch (Exception e) {
            deletePropertiesFileQuietly();
            throw fail("Failed to set up Maven native capture: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean tearDown(AbstractBuild build, BuildListener listener) {
        tornDown = true;
        try {
            addBuildInfoAction(build);
        } catch (Exception e) {
            listener.getLogger().println("[JFrog] Maven native capture: could not finish build-info " +
                    "post-processing (" + e.getMessage() + ")");
        }
        deletePropertiesFileQuietly();
        return true;
    }

    /**
     * Pattern to extract build-info URLs logged by the Maven extractor.
     * Uses a generic prefix that is version-independent and works across extractor versions.
     * The URL is then converted to Platform UI format via {@link #toPlatformUiBuildInfoUrl(String)}.
     */
    private static final Pattern BUILD_INFO_URL_PATTERN = Pattern.compile("Browse it in Artifactory under (\\S+)");

    /**
     * Same badge and sidebar link as pipeline/freestyle {@code jf rt bp}.
     * No build-page summary block.
     */
    private void addBuildInfoAction(AbstractBuild build) {
        if (resolvedServer == null) {
            return;
        }
        Result result = build.getResult();
        if (result != null && result.isWorseThan(Result.UNSTABLE)) {
            return;
        }
        String url = findPublishedBuildInfoUrl(build);
        if (StringUtils.isBlank(url)) {
            return;
        }
        build.addAction(publishedBuildInfoAction(url));
    }

    static hudson.model.Action publishedBuildInfoAction(String url) {
        return new BuildInfoBuildBadgeAction(url);
    }

    private static String findPublishedBuildInfoUrl(AbstractBuild build) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(build.getLogFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = BUILD_INFO_URL_PATTERN.matcher(line);
                if (matcher.find()) {
                    return toPlatformUiBuildInfoUrl(matcher.group(1));
                }
            }
        } catch (IOException ignored) {
            // Best-effort only; no console log to scan yet is not an error.
        }
        return null;
    }

    /**
     * Maven extractor still logs the Artifactory 6 UI ({@code /artifactory/webapp/builds}).
     * Platform instances serve the same build at {@code /ui/builds}.
     */
    static String toPlatformUiBuildInfoUrl(String browseUrl) {
        if (StringUtils.isBlank(browseUrl)) {
            return browseUrl;
        }
        return browseUrl.replace("/artifactory/webapp/builds/", "/ui/builds/");
    }

    private ArtifactoryClientConfiguration buildClientConfiguration(JFrogPlatformInstance server, EnvVars env)
            throws IOException {
        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(new NullLog());
        configuration.publisher.setMaven(true);

        // Deploy server is optional (resolution-only). Resolve server/repo are required above.
        if (server == null) {
            applyPublisherArtifactFlags(configuration, false, false);
        } else {
            Credentials credentials = resolveCredentials(server, "Maven native capture");
            configuration.publisher.setContextUrl(server.inferArtifactoryUrl());

            if (publisherNeedsTargetRepo(reporter.isDeployArtifacts(), reporter.isPublishBuildInfo())) {
                String releaseRepo = expand(env, reporter.getReleaseRepo());
                if (StringUtils.isBlank(releaseRepo)) {
                    throw fail("Release Repository is empty. Required when Deploy Artifacts is enabled.");
                }
                String snapshotRepo = expandOr(env, reporter.getSnapshotRepo(), releaseRepo);
                configuration.publisher.setRepoKey(releaseRepo);
                configuration.publisher.setSnapshotRepoKey(snapshotRepo);

                if (reporter.isDeployArtifacts()) {
                    String artifactIncludePatterns = expand(env, reporter.getArtifactIncludePatterns());
                    if (StringUtils.isNotBlank(artifactIncludePatterns)) {
                        configuration.publisher.setIncludePatterns(artifactIncludePatterns);
                    }
                    String artifactExcludePatterns = expand(env, reporter.getArtifactExcludePatterns());
                    if (StringUtils.isNotBlank(artifactExcludePatterns)) {
                        configuration.publisher.setExcludePatterns(artifactExcludePatterns);
                    }
                    String deploymentProperties = expand(env, reporter.getDeploymentProperties());
                    if (StringUtils.isNotBlank(deploymentProperties)) {
                        configuration.publisher.addMatrixParams(parseDeploymentProperties(deploymentProperties));
                    }
                }
            }
            applyPublisherArtifactFlags(configuration, reporter.isDeployArtifacts(), reporter.isPublishBuildInfo());
            applyCredentials(configuration.publisher, credentials);
            applyProxy(configuration, server.inferArtifactoryUrl());
            // Artifacts excluded from deploy should not appear in published build info either.
            configuration.publisher.setFilterExcludedArtifactsFromBuild(true);
        }

        // Resolver never falls back to the deploy server or deploy repo.
        String resolveRepo = expand(env, reporter.getResolveRepo());
        if (StringUtils.isBlank(resolveRepo)) {
            throw fail("Resolve Repository is empty.");
        }
        requireMavenVersionForResolution(detectMavenVersion(build, env, listener));
        JFrogPlatformInstance resolverServer = reporter.findResolveServer();
        if (resolverServer == null) {
            throw fail("JFrog Resolve Server '" + StringUtils.defaultString(reporter.getResolveServerId()) +
                    "' is not configured under Manage Jenkins -> System -> JFrog Platform.");
        }
        String resolveSnapshotRepo = expandOr(env, reporter.getResolveSnapshotRepo(), resolveRepo);
        configuration.resolver.setContextUrl(resolverServer.inferArtifactoryUrl());
        configuration.resolver.setRepoKey(resolveRepo);
        configuration.resolver.setDownloadSnapshotRepoKey(resolveSnapshotRepo);
        configuration.resolver.setMaven(true);
        applyCredentials(configuration.resolver, resolveCredentials(resolverServer, "Maven native capture (resolver)"));
        applyProxy(configuration, resolverServer.inferArtifactoryUrl());
        listener.getLogger().println("[JFrog] Maven native capture: resolving dependencies from '" +
                resolveRepo + "' (snapshots: '" + resolveSnapshotRepo + "') on server '" +
                resolverServer.getId() + "'.");

        if (server != null && reporter.isPublishBuildInfo()) {
            if (reporter.isCaptureEnvVars()) {
                String includePatterns = expand(env, reporter.getEnvVarsIncludePatterns());
                String excludePatterns = expand(env, reporter.getEnvVarsExcludePatterns());
                if (StringUtils.isBlank(includePatterns)) {
                    includePatterns = "*";
                }
                if (StringUtils.isBlank(excludePatterns)) {
                    excludePatterns = "*password*;*psw*;*secret*;*key*;*token*;*auth*";
                }
                applyCapturedEnvVars(configuration, env, includePatterns, excludePatterns);
            }

            if (reporter.isCaptureVcs()) {
                setIfPresent(configuration.info::setVcsUrl, env.get("GIT_URL"));
                setIfPresent(configuration.info::setVcsRevision, env.get("GIT_COMMIT"));
                setIfPresent(configuration.info::setVcsBranch, env.get("GIT_BRANCH"));
            }

            String buildName = MavenBuildIdentifiers.resolveBuildName(
                    reporter.getBuildName(), env, build.getParent().getFullName());
            String buildNumber = MavenBuildIdentifiers.resolveBuildNumber(
                    reporter.getBuildNumber(), env, String.valueOf(build.getNumber()));
            resolvedServer = server;
            configuration.info.setBuildName(buildName);
            configuration.info.setBuildNumber(buildNumber);
            setIfPresent(configuration.info::setBuildUrl, MavenBuildIdentifiers.resolveBuildUrl(env));
            setIfPresent(configuration.info::setProject, expand(env, reporter.getProject()));
        }
        configuration.setActivateRecorder(reporter.shouldConfigurePublisher());
        return configuration;
    }

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

    static boolean publisherNeedsTargetRepo(boolean deployArtifacts, boolean publishBuildInfo) {
        return deployArtifacts;
    }

    static void applyPublisherArtifactFlags(ArtifactoryClientConfiguration configuration, boolean deployArtifacts,
                                            boolean publishBuildInfo) {
        configuration.publisher.setPublishArtifacts(deployArtifacts);
        configuration.publisher.setPublishBuildInfo(publishBuildInfo);
        // Extractor defaults this to true and then requires a target repo when recording artifacts.
        configuration.publisher.setBooleanValue(ADD_DEPLOYABLE_ARTIFACTS, deployArtifacts);
    }

    static void applyCapturedEnvVars(ArtifactoryClientConfiguration configuration, Map<String, String> env,
                                     String includePatterns, String excludePatterns) {
        configuration.setIncludeEnvVars(Boolean.TRUE);
        configuration.setEnvVarsIncludePatterns(toExtractorEnvPatterns(includePatterns, true));
        configuration.setEnvVarsExcludePatterns(toExtractorEnvPatterns(excludePatterns, false));
        configuration.info.addBuildVariables(
                capturedJenkinsEnvVars(env, includePatterns, excludePatterns),
                envVarPatterns(includePatterns, excludePatterns));
    }

    /**
     * The Maven extractor splits include/exclude strings on comma/space only, then matches the full
     * {@code buildInfo.env.NAME} keys. Convert UI patterns so those keys survive filtering.
     */
    static String toExtractorEnvPatterns(String patterns, boolean prefixWildcard) {
        String[] parts = splitPatterns(patterns);
        if (parts.length == 0) {
            return patterns;
        }
        List<String> converted = new ArrayList<>();
        for (String part : parts) {
            if (prefixWildcard && !part.startsWith("*")) {
                converted.add("*" + part);
            } else {
                converted.add(part);
            }
        }
        return String.join(",", converted);
    }

    static Map<String, String> capturedJenkinsEnvVars(Map<String, String> env, String includePatterns,
                                                      String excludePatterns) {
        IncludeExcludePatterns patterns = envVarPatterns(includePatterns, excludePatterns);
        Map<String, String> captured = new LinkedHashMap<>();
        Map<String, String> systemEnv = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (systemEnv.containsKey(entry.getKey())) {
                continue;
            }
            if (PatternMatcher.pathConflicts(entry.getKey(), patterns)) {
                continue;
            }
            captured.put(entry.getKey(), entry.getValue());
        }
        return captured;
    }

    static IncludeExcludePatterns envVarPatterns(String includePatterns, String excludePatterns) {
        return new IncludeExcludePatterns(splitPatterns(includePatterns), splitPatterns(excludePatterns));
    }

    static String[] splitPatterns(String patterns) {
        if (StringUtils.isBlank(patterns)) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        for (String part : patterns.split("[;,]")) {
            if (StringUtils.isNotBlank(part)) {
                parts.add(part.trim());
            }
        }
        return parts.toArray(new String[0]);
    }

    private static String expand(EnvVars env, String value) {
        return env.expand(StringUtils.defaultString(value));
    }

    private static String expandOr(EnvVars env, String value, String fallback) {
        String expanded = expand(env, value);
        return StringUtils.isBlank(expanded) ? fallback : expanded;
    }

    private static void setIfPresent(Consumer<String> setter, String value) {
        if (StringUtils.isNotBlank(value)) {
            setter.accept(value);
        }
    }

    /**
     * Keep the encrypted properties file on the node root. A first-build SCM checkout often
     * replaces the workspace after env setup has already written the file there.
     */
    static FilePath propertiesDirectory(FilePath workspace, FilePath nodeRoot) {
        if (nodeRoot != null) {
            return nodeRoot;
        }
        return workspace;
    }

    private static IllegalStateException fail(String message) {
        return fail(message, null);
    }

    private static IllegalStateException fail(String message, Throwable cause) {
        return new IllegalStateException("[JFrog] Maven native capture: " + message, cause);
    }

    /**
     * Fail closed because an unknown Maven version cannot be checked against the resolver's
     * supported range and may silently fall back to Central.
     */
    static void requireMavenVersionForResolution(String version) {
        if (StringUtils.isBlank(version)) {
            throw fail("could not determine the Maven version. Resolving from Artifactory requires Maven 3.0.2 or higher.");
        }
        assertMavenVersionSupportsResolution(version);
    }

    /**
     * Reads maven-core-&lt;version&gt;.jar from the Maven tool home.
     * Do not pass an EnvVars obtained via {@code build.getEnvironment()} from inside
     * {@link #buildEnvVars(Map)} — that re-enters this Environment and recurses.
     */
    /**
     * Detect the Maven version by inspecting maven-core-*.jar in the Maven home lib directory.
     *
     * <p><strong>Recursion Safety:</strong> When called from {@code PlexusModuleContributorFactory.createFor()},
     * which is invoked during build environment setup before {@code buildEnvVars()} is called,
     * the call to {@code build.getEnvironment(TaskListener.NULL)} here is safe.
     * {@code PlexusModuleContributorFactory} runs after environment setup is complete but before
     * the build launches, so it does not trigger a re-entrant call to {@code buildEnvVars()}.
     * Always pass a pre-computed {@code EnvVars} to avoid calling {@code build.getEnvironment()} again.</p>
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
            FilePath channelBase = propertiesDirectory(build.getWorkspace(),
                    node != null ? node.getRootPath() : null);
            if (channelBase == null) {
                return null;
            }
            FilePath homePath = new FilePath(channelBase.getChannel(), home);
            FilePath[] coreJars = homePath.child("lib").list("maven-core-*.jar");
            if (coreJars == null || coreJars.length == 0) {
                return null;
            }
            Pattern corePattern = Pattern.compile("maven-core-(.+)\\.jar");
            for (FilePath jar : coreJars) {
                Matcher matcher = corePattern.matcher(jar.getName());
                if (matcher.matches()) {
                    return matcher.group(1);
                }
            }
            return null;
        } catch (Exception e) {
            listener.getLogger().println("[JFrog] Maven native capture: could not determine Maven version (" +
                    e.getMessage() + ").");
            return null;
        }
    }

    static void assertMavenVersionSupportsResolution(String version) {
        ComparableVersion found = new ComparableVersion(version);
        ComparableVersion minimum = new ComparableVersion("3.0.2");
        if (found.compareTo(minimum) < 0) {
            throw fail("resolving dependencies from Artifactory requires Maven 3.0.2 or higher. Detected: " +
                    version + ".");
        }
    }

    private Credentials resolveCredentials(JFrogPlatformInstance server, String context) {
        CredentialsConfig credentialsConfig = server.getCredentialsConfig();
        if (credentialsConfig == null || StringUtils.isBlank(credentialsConfig.getCredentialsId())) {
            throw fail(context + ": server '" + server.getId() + "' has no credentials configured.");
        }
        String credentialsId = credentialsConfig.getCredentialsId();
        Item lookupContext = build.getParent();

        // Folder-scoped override matches the CLI path in JfStep.addCredentialsArguments().
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
                throw fail(context + ": folder-level credentials override for server '" + server.getId() +
                        "' in folder '" + folderOverride.getContext().getFullName() +
                        "' resolved to no credentials (id '" + credentialsId +
                        "'). The credential may have been deleted or renamed.");
            }
            throw fail(context + ": credentials id '" + credentialsId + "' could not be resolved.");
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
            throw fail("resolved credentials are empty.");
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
            // Same wildcard-to-suffix normalization CliEnvConfigurator uses for the CLI path.
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
     * Injects extractor jars into Maven's plexus.core realm after verifying that the Maven version
     * is at least 3.0.2.
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
            if (StringUtils.isBlank(reporter.getResolveRepo())) {
                throw new IOException("[JFrog] Maven native capture: Resolve Repository is required.");
            }
            EnvVars env;
            try {
                env = context.getEnvironment(TaskListener.NULL);
            } catch (Exception e) {
                env = new EnvVars();
            }
            try {
                requireMavenVersionForResolution(detectMavenVersion(context, env, TaskListener.NULL));
            } catch (IllegalStateException e) {
                throw new IOException(e.getMessage(), e);
            }

            FilePath dependencyDir = PluginDependencyHelper.getActualDependencyDirectory(node.getRootPath());
            List<FilePath> selected = new ArrayList<>();
            for (String name : PluginDependencyHelper.readExtractorJarNames(
                    PluginDependencyHelper.class.getClassLoader())) {
                selected.add(new FilePath(dependencyDir, name));
            }
            return PlexusModuleContributor.of(selected);
        }
    }
}
