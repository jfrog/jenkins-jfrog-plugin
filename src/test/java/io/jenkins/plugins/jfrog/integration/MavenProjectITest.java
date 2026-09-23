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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end Maven Project job: native capture, artifact deploy, build-info publish,
 * then download and verify the published build-info and the deployed jar.
 */
class MavenProjectITest extends PipelineTestBase {

    private static final String GROUP_ID = "io.jenkins.plugins.jfrog.test";
    private static final String ARTIFACT_ID = "maven-native-it";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static void configureMavenInstallation(JenkinsRule jenkins) {
        String mavenHome = resolveMavenHome();
        if (StringUtils.isBlank(mavenHome)) {
            fail("Could not resolve a Maven installation. Set maven.home, MAVEN_HOME, or M2_HOME.");
        }
        Maven.MavenInstallation installation =
                new Maven.MavenInstallation("default-maven", mavenHome, Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(Maven.DescriptorImpl.class).setInstallations(installation);
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
