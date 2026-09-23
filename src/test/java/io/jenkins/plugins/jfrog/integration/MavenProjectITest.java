package io.jenkins.plugins.jfrog.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.tasks.Maven;
import io.jenkins.plugins.jfrog.maven.MavenArtifactoryReporter;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.artifactory.client.ArtifactoryRequest;
import org.jfrog.artifactory.client.ArtifactoryResponse;
import org.jfrog.artifactory.client.impl.ArtifactoryRequestImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end Maven Project job: native capture, artifact deploy, build-info publish,
 * then download and verify the published build-info and the deployed jar.
 */
class MavenProjectITest extends PipelineTestBase {

    private static final String GROUP_ID = "io.jenkins.plugins.jfrog.test";
    private static final String ARTIFACT_ID = "maven-native-it";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Known-good per jfrog/build-info#841 (extractor NPEs on Maven 3.9.12+). Pinned and downloaded
    // rather than relying on the ambient/system Maven, which is not guaranteed to be in range on a
    // given CI runner or developer machine (e.g. GitHub Actions ships 3.9.12+ on its Linux image).
    private static final String RESOLUTION_COMPATIBLE_MAVEN_VERSION = "3.9.9";
    private static final String RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME = "resolution-compatible-maven";

    @Test
    public void testMavenProjectPublishesBuildInfoAndArtifacts(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String moduleId = GROUP_ID + ":" + ARTIFACT_ID + ":" + version;
        String jarName = ARTIFACT_ID + "-" + version + ".jar";
        String jarPath = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version + "/" + jarName;

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setArtifactoryRepo(repoKey);
        reporter.setDeployArtifacts(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it");
        project.setMaven("default-maven");
        // build-info-extractor-maven3 skips deploy/publish unless goals include install or deploy.
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPom(version)));
        project.getReporters().add(reporter);

        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertTrue(log.contains("[JFrog] Maven native capture enabled"), log);
        assertTrue(log.contains("server: " + TEST_CONFIGURED_SERVER_ID), log);
        assertTrue(log.contains("repo: " + repoKey), log);
        assertTrue(log.contains("deployArtifacts: true"), log);
        assertFalse(log.contains("-DPROPERTIES_FILE_KEY="), log);
        assertFalse(log.contains("-DPROPERTIES_FILE_KEY_IV="), log);

        JsonNode published = downloadBuildInfo(buildName, buildNumber);
        try {
            assertEquals(buildName, published.path("name").asText());
            assertEquals(buildNumber, published.path("number").asText());

            JsonNode modules = published.path("modules");
            assertTrue(modules.isArray() && modules.size() > 0, "Published build-info has no modules: " + published);
            JsonNode module = findModule(modules, moduleId);
            assertEquals(moduleId, module.path("id").asText());

            JsonNode artifacts = module.path("artifacts");
            assertTrue(hasArtifact(artifacts, jarName), "Missing jar in build-info artifacts: " + artifacts);
            assertTrue(hasArtifact(artifacts, ARTIFACT_ID + "-" + version + ".pom"),
                    "Missing pom in build-info artifacts: " + artifacts);

            byte[] jar = downloadArtifact(repoKey, jarPath);
            assertTrue(jar.length > 0, "Downloaded jar is empty");
            assertEquals('P', (char) jar[0]);
            assertEquals('K', (char) jar[1]);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectDeploysReleaseAndSnapshotToSeparateRepos(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureMavenInstallation(jenkins);

        String version = "1.0.0-SNAPSHOT";
        String buildName = "rteco-1662-maven-native-it-snapshot";
        String buildNumber = "it-" + System.currentTimeMillis();
        String releaseRepoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String snapshotRepoKey = getRepoKey(TestRepository.MAVEN_SNAPSHOT_REPO);
        String jarName = ARTIFACT_ID + "-" + version + ".jar";
        String jarPath = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version + "/" + jarName;

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setArtifactoryRepo(releaseRepoKey);
        reporter.setSnapshotRepo(snapshotRepoKey);
        reporter.setDeployArtifacts(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-snapshot");
        project.setMaven("default-maven");
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPom(version)));
        project.getReporters().add(reporter);

        jenkins.buildAndAssertSuccess(project);
        try {
            // The snapshot repo is configured with handleReleases=false, so if snapshotRepoKey
            // wiring were broken and the deploy fell back to the release repo key, this download
            // would fail rather than silently passing.
            byte[] jar = downloadArtifact(snapshotRepoKey, jarPath);
            assertTrue(jar.length > 0, "Downloaded snapshot jar is empty");
            assertEquals('P', (char) jar[0]);
            assertEquals('K', (char) jar[1]);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectAppliesDeploymentProperties(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-props";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String jarPath = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version + "/"
                + ARTIFACT_ID + "-" + version + ".jar";

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setArtifactoryRepo(repoKey);
        reporter.setDeployArtifacts(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);
        reporter.setDeploymentProperties("status=staging;region=us");

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-props");
        project.setMaven("default-maven");
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPom(version)));
        project.getReporters().add(reporter);

        jenkins.buildAndAssertSuccess(project);
        try {
            JsonNode properties = downloadItemProperties(repoKey, jarPath);
            assertEquals("staging", properties.path("status").get(0).asText());
            assertEquals("us", properties.path("region").get(0).asText());
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectExcludesArtifactsMatchingPattern(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-exclude";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String basePath = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version + "/";
        String jarPath = basePath + ARTIFACT_ID + "-" + version + ".jar";
        String pomPath = basePath + ARTIFACT_ID + "-" + version + ".pom";

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setArtifactoryRepo(repoKey);
        reporter.setDeployArtifacts(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);
        reporter.setArtifactExcludePatterns("*.pom");

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-exclude");
        project.setMaven("default-maven");
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPom(version)));
        project.getReporters().add(reporter);

        jenkins.buildAndAssertSuccess(project);
        try {
            byte[] jar = downloadArtifact(repoKey, jarPath);
            assertTrue(jar.length > 0, "Downloaded jar is empty");

            assertThrows(Exception.class, () -> downloadArtifact(repoKey, pomPath),
                    "The .pom was excluded by pattern and should not have been deployed");
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }


    @Test
    public void testMavenProjectResolvesDependenciesFromArtifactory(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        // Resolve Repository requires a Maven version the extractor doesn't NPE on (jfrog/build-info#841
        // rejects 3.9.12+). The ambient/system Maven on a given CI runner or developer machine is not
        // guaranteed to be in range - download a known-compatible version instead of relying on it.
        configureResolutionCompatibleMavenInstallation(jenkins);

        String depVersion = "1.0.0-resolve-" + System.currentTimeMillis();
        String depGroup = GROUP_ID;
        String depArtifact = "maven-native-resolve-dep";
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        // MAVEN_LOCAL_REPO has no upstream. Once Resolve Repository is configured, it takes over
        // ALL Maven resolution - including core lifecycle plugins like maven-clean-plugin, not
        // just this test's own dependency - so a bare local repo leaves nowhere to resolve them
        // from. Resolve against the virtual repo (local + a Central-proxying remote) instead;
        // deployment still targets the local repo directly.
        String resolveRepoKey = getRepoKey(TestRepository.MAVEN_VIRTUAL_REPO);

        // Seed a dependency only available in Artifactory (not Central).
        uploadMavenArtifact(repoKey, depGroup, depArtifact, depVersion);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-resolve";
        String buildNumber = "it-" + System.currentTimeMillis();

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setArtifactoryRepo(repoKey);
        reporter.setResolveRepo(resolveRepoKey);
        reporter.setDeployArtifacts(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-resolve");
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        // Private repo forces resolution through the extractor override, not the agent's settings.xml.
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPomWithDependency(version, depGroup, depArtifact, depVersion)));
        project.getReporters().add(reporter);

        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertTrue(log.contains("resolving dependencies from '" + resolveRepoKey + "'"), log);
        try {
            JsonNode published = downloadBuildInfo(buildName, buildNumber);
            JsonNode modules = published.path("modules");
            assertTrue(modules.isArray() && modules.size() > 0, "Published build-info has no modules: " + published);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    private static void configureMavenInstallation(JenkinsRule jenkins) {
        String mavenHome = resolveMavenHome();
        if (StringUtils.isBlank(mavenHome)) {
            fail("Could not resolve a Maven installation. Set maven.home, MAVEN_HOME, or M2_HOME.");
        }
        Maven.MavenInstallation installation =
                new Maven.MavenInstallation("default-maven", mavenHome, Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(Maven.DescriptorImpl.class).setInstallations(installation);
    }

    /**
     * Downloads (and caches under {@code target/}) a Maven version known to work with Resolve
     * Repository, independent of whatever Maven happens to be ambiently installed on the machine
     * running the test. Skips the test (does not fail it) if the download can't complete - e.g.
     * no network access in a sandboxed CI environment - rather than blocking unrelated CI runs on
     * an environment limitation.
     */
    private static void configureResolutionCompatibleMavenInstallation(JenkinsRule jenkins) throws Exception {
        String mavenHome = resolveOrDownloadCompatibleMavenHome();
        Maven.MavenInstallation installation = new Maven.MavenInstallation(
                RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME, mavenHome, Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(Maven.DescriptorImpl.class).setInstallations(installation);
    }

    private static String resolveOrDownloadCompatibleMavenHome() throws Exception {
        Path cacheRoot = Paths.get("target", "resolution-compatible-maven").toAbsolutePath();
        Path installDir = cacheRoot.resolve("apache-maven-" + RESOLUTION_COMPATIBLE_MAVEN_VERSION);
        Path mvnExecutable = installDir.resolve("bin").resolve("mvn");
        if (Files.isExecutable(mvnExecutable) || Files.exists(installDir.resolve("bin").resolve("mvn.cmd"))) {
            return installDir.toString();
        }

        Files.createDirectories(cacheRoot);
        Path archive = cacheRoot.resolve("apache-maven-" + RESOLUTION_COMPATIBLE_MAVEN_VERSION + "-bin.tar.gz");
        String downloadUrl = "https://archive.apache.org/dist/maven/maven-3/" + RESOLUTION_COMPATIBLE_MAVEN_VERSION
                + "/binaries/apache-maven-" + RESOLUTION_COMPATIBLE_MAVEN_VERSION + "-bin.tar.gz";
        try {
            try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                Files.copy(in, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            assumeTrue(false, "Could not download Maven " + RESOLUTION_COMPATIBLE_MAVEN_VERSION
                    + " for the Resolve Repository test (no network access?): " + e.getMessage());
        }

        // `tar` is present on every Linux/macOS CI/dev machine and on Windows 10 1803+ / GitHub
        // Actions windows-latest images - no extra library or OS package required.
        Process extract = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", cacheRoot.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(extract.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = extract.waitFor();
        Files.deleteIfExists(archive);
        if (exitCode != 0) {
            fail("Failed to extract downloaded Maven " + RESOLUTION_COMPATIBLE_MAVEN_VERSION + ": " + output);
        }
        return installDir.toString();
    }

    private static String resolveMavenHome() {
        String mavenHome = System.getProperty("maven.home");
        if (StringUtils.isNotBlank(mavenHome)) {
            return mavenHome;
        }
        mavenHome = StringUtils.firstNonBlank(System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"));
        if (StringUtils.isNotBlank(mavenHome)) {
            return mavenHome;
        }
        return detectMavenHomeFromCli();
    }

    private static String detectMavenHomeFromCli() {
        try {
            Process process = new ProcessBuilder("mvn", "-v").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                return null;
            }
            for (String line : output.split("\\R")) {
                if (line.startsWith("Maven home:")) {
                    return line.substring("Maven home:".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // Fall through to the missing-installation assertion.
        }
        return null;
    }


    private static void uploadMavenArtifact(String repoKey, String groupId, String artifactId, String version)
            throws Exception {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/";
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(groupId, artifactId, version);
        // Minimal empty jar (EOCD only).
        byte[] jar = new byte[]{0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        getArtifactoryClient().repository(repoKey)
                .upload(path + artifactId + "-" + version + ".pom",
                        new java.io.ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)))
                .doUpload();
        getArtifactoryClient().repository(repoKey)
                .upload(path + artifactId + "-" + version + ".jar",
                        new java.io.ByteArrayInputStream(jar))
                .doUpload();
    }

    private static String mavenPomWithDependency(String version, String depGroup, String depArtifact, String depVersion) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(GROUP_ID, ARTIFACT_ID, version, depGroup, depArtifact, depVersion);
    }

    private static String mavenPom(String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>JFrog Maven native IT</name>
                </project>
                """.formatted(GROUP_ID, ARTIFACT_ID, version);
    }

    private static JsonNode downloadBuildInfo(String buildName, String buildNumber) throws Exception {
        ArtifactoryResponse response = getArtifactoryClient().restCall(new ArtifactoryRequestImpl()
                .method(ArtifactoryRequest.Method.GET)
                .responseType(ArtifactoryRequest.ContentType.JSON)
                .apiUrl("api/build/" + encode(buildName) + "/" + encode(buildNumber)));
        assertTrue(response.isSuccessResponse(),
                "Failed to download build-info " + buildName + "/" + buildNumber + ": " + response.getStatusLine()
                        + " " + response.getRawBody());
        JsonNode root = MAPPER.readTree(response.getRawBody());
        JsonNode buildInfo = root.path("buildInfo");
        assertFalse(buildInfo.isMissingNode(), "Response has no buildInfo: " + response.getRawBody());
        return buildInfo;
    }

    private static byte[] downloadArtifact(String repoKey, String path) throws Exception {
        try (InputStream in = getArtifactoryClient().repository(repoKey).download(path).doDownload()) {
            return in.readAllBytes();
        }
    }

    private static JsonNode downloadItemProperties(String repoKey, String path) throws Exception {
        ArtifactoryResponse response = getArtifactoryClient().restCall(new ArtifactoryRequestImpl()
                .method(ArtifactoryRequest.Method.GET)
                .responseType(ArtifactoryRequest.ContentType.JSON)
                .apiUrl("api/storage/" + repoKey + "/" + path + "?properties"));
        assertTrue(response.isSuccessResponse(),
                "Failed to fetch properties for " + repoKey + "/" + path + ": " + response.getStatusLine()
                        + " " + response.getRawBody());
        JsonNode root = MAPPER.readTree(response.getRawBody());
        JsonNode properties = root.path("properties");
        assertFalse(properties.isMissingNode(), "Response has no properties: " + response.getRawBody());
        return properties;
    }

    private static void deleteBuildInfo(String buildName, String buildNumber) {
        try {
            getArtifactoryClient().restCall(new ArtifactoryRequestImpl()
                    .method(ArtifactoryRequest.Method.DELETE)
                    .apiUrl("api/build/" + encode(buildName) + "?buildNumbers=" + encode(buildNumber) + "&artifacts=0"));
        } catch (Exception ignored) {
            // Best-effort cleanup of the unique IT build-info.
        }
    }

    private static JsonNode findModule(JsonNode modules, String moduleId) {
        for (JsonNode module : modules) {
            if (moduleId.equals(module.path("id").asText())) {
                return module;
            }
        }
        fail("Published build-info is missing module " + moduleId + ": " + modules);
        return null;
    }

    private static boolean hasArtifact(JsonNode artifacts, String name) {
        if (!artifacts.isArray()) {
            return false;
        }
        for (JsonNode artifact : artifacts) {
            if (name.equals(artifact.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
