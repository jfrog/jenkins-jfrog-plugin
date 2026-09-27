package io.jenkins.plugins.jfrog.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.tasks.Maven;
import io.jenkins.plugins.jfrog.JfrogBuildInfoPublisher;
import io.jenkins.plugins.jfrog.maven.MavenArtifactoryReporter;
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

    // Pinned above 3.9.12 so ITs cover extractor 2.43.9 (build-info#841). Downloaded rather than
    // relying on the ambient Maven on a given CI runner.
    private static final String RESOLUTION_COMPATIBLE_MAVEN_VERSION = "3.9.16";
    private static final String RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME = "resolution-compatible-maven";

    @Test
    public void testMavenProjectPublishesBuildInfoAndArtifacts(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it");
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
        // build-info-extractor-maven3 skips deploy/publish unless goals include install or deploy.
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPom(version)));
        project.getReporters().add(reporter);
        project.getPublishersList().add(new JfrogBuildInfoPublisher());

        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertTrue(log.contains("[JFrog] Maven native capture enabled"), log);
        assertTrue(log.contains("server: " + TEST_CONFIGURED_SERVER_ID), log);
        assertTrue(log.contains("repo: " + repoKey), log);
        assertTrue(log.contains("deployArtifacts: true"), log);
        assertTrue(log.contains("Skipping CLI publish for Maven Project jobs"), log);
        assertFalse(log.contains("-DPROPERTIES_FILE_KEY="), log);
        assertFalse(log.contains("-DPROPERTIES_FILE_KEY_IV="), log);

        JsonNode published = downloadBuildInfo(buildName, buildNumber);
        try {
            assertEquals(buildName, published.path("name").asText());
            assertEquals(buildNumber, published.path("number").asText());
            assertBuildInfoHasPublishedArtifacts(published, version, true);
            assertJarInArtifactory(repoKey, version);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectDeploysReleaseAndSnapshotToSeparateRepos(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-SNAPSHOT";
        String buildName = "rteco-1662-maven-native-it-snapshot";
        String buildNumber = "it-" + System.currentTimeMillis();
        String releaseRepoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String snapshotRepoKey = getRepoKey(TestRepository.MAVEN_SNAPSHOT_REPO);
        String jarPath = artifactPath(version, "jar");

        MavenArtifactoryReporter reporter = nativeReporter(releaseRepoKey, buildName, buildNumber);
        reporter.setSnapshotRepo(snapshotRepoKey);

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-snapshot");
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
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
    public void testMavenProjectDeploysSnapshotToReleaseRepoWhenSnapshotRepoIsEmpty(JenkinsRule jenkins)
            throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis() + "-SNAPSHOT";
        String buildName = "rteco-1662-maven-native-it-empty-snap";
        String buildNumber = "it-" + System.currentTimeMillis();
        String releaseRepoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String snapshotRepoKey = getRepoKey(TestRepository.MAVEN_SNAPSHOT_REPO);
        String jarPath = artifactPath(version, "jar");

        MavenArtifactoryReporter reporter = nativeReporter(releaseRepoKey, buildName, buildNumber);
        reporter.setSnapshotRepo("");

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-empty-snap", reporter, mavenPom(version));
        jenkins.buildAndAssertSuccess(project);
        try {
            byte[] jar = downloadMavenArtifactAllowingUniqueSnapshots(releaseRepoKey, version, "jar");
            assertTrue(jar.length > 0, "Empty Snapshot Repository should reuse Release Repository");
            assertThrows(Exception.class, () -> downloadArtifact(snapshotRepoKey, jarPath),
                    "Empty Snapshot Repository must not deploy to the dedicated snapshot repo");
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectAppliesDeploymentProperties(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-props";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String jarPath = artifactPath(version, "jar");

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setDeploymentProperties("status=staging;region=us");

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-props");
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
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
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-exclude";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String jarPath = artifactPath(version, "jar");
        String pomPath = artifactPath(version, "pom");

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setArtifactExcludePatterns("*.pom");

        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-native-it-exclude");
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
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
        // Download a known Maven rather than relying on whatever is ambient on the CI runner.
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

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setResolveRepo(resolveRepoKey);

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
            assertBuildInfoHasPublishedArtifacts(published, version, true);
            assertBuildInfoHasDependency(published, version, depGroup, depArtifact);
            assertJarInArtifactory(repoKey, version);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectIncludesOnlyMatchingArtifacts(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-include";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setArtifactIncludePatterns("*.jar");

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-include", reporter, mavenPom(version));
        jenkins.buildAndAssertSuccess(project);
        try {
            byte[] jar = downloadArtifact(repoKey, artifactPath(version, "jar"));
            assertTrue(jar.length > 0, "Downloaded jar is empty");
            assertThrows(Exception.class, () -> downloadArtifact(repoKey, artifactPath(version, "pom")),
                    "The .pom was outside the include pattern and should not have been deployed");
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectDoesNotDeployWhenDeployArtifactsIsFalse(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-no-deploy";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setDeployArtifacts(false);

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-no-deploy", reporter, mavenPom(version));
        jenkins.buildAndAssertSuccess(project);
        try {
            JsonNode published = downloadBuildInfo(buildName, buildNumber);
            assertEquals(buildName, published.path("name").asText());
            assertThrows(Exception.class, () -> downloadArtifact(repoKey, artifactPath(version, "jar")),
                    "deployArtifacts=false should not upload the jar");
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectPublishesBuildInfoWithoutReleaseRepo(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-bi-no-repo";
        String buildNumber = "it-" + System.currentTimeMillis();

        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setResolveServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setResolveRepo(getRepoKey(TestRepository.MAVEN_VIRTUAL_REPO));
        reporter.setDeployArtifacts(false);
        reporter.setPublishBuildInfo(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-bi-no-repo", reporter, mavenPom(version));
        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertFalse(log.contains("Release Repository is empty"), log);
        assertFalse(log.contains("Target repository cannot be empty"), log);
        try {
            JsonNode published = downloadBuildInfo(buildName, buildNumber);
            assertEquals(buildName, published.path("name").asText());
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectDoesNotPublishBuildInfoWhenDisabled(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-no-bi";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setPublishBuildInfo(false);

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-no-bi", reporter, mavenPom(version));
        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        assertFalse(build.getLog().contains("Skipping CLI publish for Maven Project jobs"), build.getLog());
        try {
            byte[] jar = downloadArtifact(repoKey, artifactPath(version, "jar"));
            assertTrue(jar.length > 0, "Downloaded jar is empty");
            assertBuildInfoMissing(buildName, buildNumber);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectCapturesEnvVarsAndVcs(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String marker = "it-env-" + System.currentTimeMillis();
        String vcsUrl = "https://example.com/maven-native-it.git";
        String vcsRevision = "abc123def456";
        String vcsBranch = "refs/heads/it";
        jenkins.jenkins.getGlobalNodeProperties().add(new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry("JFROG_IT_MARKER", marker),
                new EnvironmentVariablesNodeProperty.Entry("JFROG_IT_PASSWORD", "should-be-excluded"),
                new EnvironmentVariablesNodeProperty.Entry("GIT_URL", vcsUrl),
                new EnvironmentVariablesNodeProperty.Entry("GIT_COMMIT", vcsRevision),
                new EnvironmentVariablesNodeProperty.Entry("GIT_BRANCH", vcsBranch)));

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-meta";
        String buildNumber = "it-" + System.currentTimeMillis();
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setCaptureEnvVars(true);
        reporter.setEnvVarsIncludePatterns("JFROG_IT_MARKER;GIT_*");
        reporter.setEnvVarsExcludePatterns("*password*;*psw*;*secret*;*key*;*token*;*auth*");
        reporter.setCaptureVcs(true);

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-meta", reporter, mavenPom(version));
        jenkins.buildAndAssertSuccess(project);
        try {
            JsonNode published = downloadBuildInfo(buildName, buildNumber);
            assertTrue(jsonTreeContains(published, marker),
                    "Published build-info is missing captured env var " + marker + ": " + published);
            assertFalse(jsonTreeContains(published, "should-be-excluded"),
                    "Excluded secret env var leaked into build-info: " + published);
            assertTrue(jsonTreeContains(published, vcsUrl), "Published build-info is missing VCS URL: " + published);
            assertTrue(jsonTreeContains(published, vcsRevision),
                    "Published build-info is missing VCS revision: " + published);
            assertTrue(jsonTreeContains(published, vcsBranch),
                    "Published build-info is missing VCS branch: " + published);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectResolvesSnapshotsFromSeparateRepo(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String depVersion = "1.0.0-" + System.currentTimeMillis() + "-SNAPSHOT";
        String depGroup = GROUP_ID;
        String depArtifact = "maven-native-resolve-snapshot-dep";
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String resolveRepoKey = getRepoKey(TestRepository.MAVEN_VIRTUAL_REPO);
        String resolveSnapshotRepoKey = getRepoKey(TestRepository.MAVEN_SNAPSHOT_REPO);

        uploadMavenArtifact(resolveSnapshotRepoKey, depGroup, depArtifact, depVersion);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-resolve-snap";
        String buildNumber = "it-" + System.currentTimeMillis();

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setResolveRepo(resolveRepoKey);
        reporter.setResolveSnapshotRepo(resolveSnapshotRepoKey);

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-resolve-snap", reporter,
                mavenPomWithDependency(version, depGroup, depArtifact, depVersion));
        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertTrue(log.contains("resolving dependencies from '" + resolveRepoKey + "' (snapshots: '"
                + resolveSnapshotRepoKey + "')"), log);
        try {
            JsonNode published = downloadBuildInfo(buildName, buildNumber);
            assertBuildInfoHasPublishedArtifacts(published, version, true);
            assertBuildInfoHasDependency(published, version, depGroup, depArtifact);
            assertJarInArtifactory(repoKey, version);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectResolvesSnapshotsFromResolveRepoWhenResolveSnapshotRepoIsEmpty(JenkinsRule jenkins)
            throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String depVersion = "1.0.0-" + System.currentTimeMillis() + "-SNAPSHOT";
        String depGroup = GROUP_ID;
        String depArtifact = "maven-native-resolve-empty-snapshot-dep";
        String repoKey = getRepoKey(TestRepository.MAVEN_LOCAL_REPO);
        String resolveRepoKey = getRepoKey(TestRepository.MAVEN_VIRTUAL_REPO);

        // Virtual includes local, not the dedicated snapshot repo. Empty Resolve Snapshot
        // Repository must reuse Resolve Repository so this seeded snapshot is found.
        uploadMavenArtifact(repoKey, depGroup, depArtifact, depVersion);

        String version = "1.0.0-" + System.currentTimeMillis();
        String buildName = "rteco-1662-maven-native-it-resolve-empty-snap";
        String buildNumber = "it-" + System.currentTimeMillis();

        MavenArtifactoryReporter reporter = nativeReporter(repoKey, buildName, buildNumber);
        reporter.setResolveRepo(resolveRepoKey);
        reporter.setResolveSnapshotRepo("");

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-resolve-empty-snap", reporter,
                mavenPomWithDependency(version, depGroup, depArtifact, depVersion));
        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertTrue(log.contains("resolving dependencies from '" + resolveRepoKey + "' (snapshots: '"
                + resolveRepoKey + "')"), log);
        try {
            JsonNode published = downloadBuildInfo(buildName, buildNumber);
            assertBuildInfoHasPublishedArtifacts(published, version, true);
            assertBuildInfoHasDependency(published, version, depGroup, depArtifact);
            assertJarInArtifactory(repoKey, version);
        } finally {
            deleteBuildInfo(buildName, buildNumber);
        }
    }

    @Test
    public void testMavenProjectResolvesWithoutDeployServer(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setResolveServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setResolveRepo(getRepoKey(TestRepository.MAVEN_VIRTUAL_REPO));
        reporter.setDeployArtifacts(false);
        reporter.setPublishBuildInfo(false);

        MavenModuleSet project = mavenNativeJob(jenkins, "maven-native-it-resolve-only", reporter, mavenPom(version));
        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertTrue(log.contains("[JFrog] Maven native capture enabled"), log);
        assertTrue(log.contains("none (resolution-only)"), log);
        assertFalse(log.contains("Target repository cannot be empty"), log);
    }

    @Test
    public void testMavenProjectWithoutReporterDoesNotEnableNativeCapture(JenkinsRule jenkins) throws Exception {
        setupJenkins(jenkins);
        configureResolutionCompatibleMavenInstallation(jenkins);

        String version = "1.0.0-" + System.currentTimeMillis();
        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, "maven-legacy-no-reporter");
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setScm(new SingleFileSCM("pom.xml", mavenPom(version)));

        MavenModuleSetBuild build = jenkins.buildAndAssertSuccess(project);
        String log = build.getLog();
        assertFalse(log.contains("[JFrog] Maven native capture enabled"), log);
        assertFalse(log.contains("Skipping CLI publish for Maven Project jobs"), log);
    }

    private static MavenArtifactoryReporter nativeReporter(String releaseRepo, String buildName, String buildNumber) {
        MavenArtifactoryReporter reporter = new MavenArtifactoryReporter();
        reporter.setServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setReleaseRepo(releaseRepo);
        reporter.setResolveServerId(TEST_CONFIGURED_SERVER_ID);
        reporter.setResolveRepo(getRepoKey(TestRepository.MAVEN_VIRTUAL_REPO));
        reporter.setDeployArtifacts(true);
        reporter.setPublishBuildInfo(true);
        reporter.setBuildName(buildName);
        reporter.setBuildNumber(buildNumber);
        return reporter;
    }

    private static MavenModuleSet mavenNativeJob(JenkinsRule jenkins, String name, MavenArtifactoryReporter reporter,
                                                 String pom) throws Exception {
        MavenModuleSet project = jenkins.createProject(MavenModuleSet.class, name);
        project.setMaven(RESOLUTION_COMPATIBLE_MAVEN_TOOL_NAME);
        project.setGoals("-B -DskipTests clean install");
        project.setDisableTriggerDownstreamProjects(true);
        project.setIsArchivingDisabled(true);
        project.setUsePrivateRepository(true);
        project.setScm(new SingleFileSCM("pom.xml", pom));
        project.getReporters().add(reporter);
        return project;
    }

    private static String artifactPath(String version, String extension) {
        return GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version + "/"
                + ARTIFACT_ID + "-" + version + "." + extension;
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

    private static void assertBuildInfoMissing(String buildName, String buildNumber) throws Exception {
        ArtifactoryResponse response = getArtifactoryClient().restCall(new ArtifactoryRequestImpl()
                .method(ArtifactoryRequest.Method.GET)
                .responseType(ArtifactoryRequest.ContentType.JSON)
                .apiUrl("api/build/" + encode(buildName) + "/" + encode(buildNumber)));
        if (!response.isSuccessResponse()) {
            return;
        }
        JsonNode root = MAPPER.readTree(response.getRawBody());
        assertTrue(root.path("buildInfo").isMissingNode(),
                "publishBuildInfo=false still published build-info: " + response.getRawBody());
    }

    private static boolean jsonTreeContains(JsonNode node, String value) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isValueNode()) {
            return node.asText().contains(value);
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().contains(value) || jsonTreeContains(entry.getValue(), value)) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode child : node) {
            if (jsonTreeContains(child, value)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] downloadArtifact(String repoKey, String path) throws Exception {
        try (InputStream in = getArtifactoryClient().repository(repoKey).download(path).doDownload()) {
            return in.readAllBytes();
        }
    }

    /**
     * Local Maven repos often store unique timestamped snapshot files. Artifactory may still serve
     * the {@code -SNAPSHOT} path; if not, pick the timestamped jar from the GAV folder.
     */
    private static byte[] downloadMavenArtifactAllowingUniqueSnapshots(String repoKey, String version, String extension)
            throws Exception {
        String path = artifactPath(version, extension);
        try {
            return downloadArtifact(repoKey, path);
        } catch (Exception first) {
            String dir = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version;
            ArtifactoryResponse response = getArtifactoryClient().restCall(new ArtifactoryRequestImpl()
                    .method(ArtifactoryRequest.Method.GET)
                    .responseType(ArtifactoryRequest.ContentType.JSON)
                    .apiUrl("api/storage/" + repoKey + "/" + dir));
            if (!response.isSuccessResponse()) {
                throw first;
            }
            JsonNode children = MAPPER.readTree(response.getRawBody()).path("children");
            String suffix = "." + extension;
            for (JsonNode child : children) {
                String uri = child.path("uri").asText();
                if (uri.endsWith(suffix) && uri.contains(ARTIFACT_ID) && !uri.contains("-sources")) {
                    return downloadArtifact(repoKey, dir + uri);
                }
            }
            throw first;
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

    private static void assertBuildInfoHasPublishedArtifacts(JsonNode published, String version, boolean expectPom) {
        JsonNode module = findModule(published.path("modules"), GROUP_ID + ":" + ARTIFACT_ID + ":" + version);
        JsonNode artifacts = module.path("artifacts");
        assertTrue(hasArtifact(artifacts, ARTIFACT_ID + "-" + version + ".jar"),
                "Build-info artifacts missing jar: " + artifacts);
        if (expectPom) {
            assertTrue(hasArtifact(artifacts, ARTIFACT_ID + "-" + version + ".pom"),
                    "Build-info artifacts missing pom: " + artifacts);
        }
    }

    private static void assertBuildInfoHasDependency(JsonNode published, String version, String depGroup,
                                                     String depArtifact) {
        JsonNode module = findModule(published.path("modules"), GROUP_ID + ":" + ARTIFACT_ID + ":" + version);
        JsonNode dependencies = module.path("dependencies");
        String prefix = depGroup + ":" + depArtifact + ":";
        assertTrue(hasDependencyIdPrefix(dependencies, prefix),
                "Build-info dependencies missing " + prefix + ": " + dependencies);
    }

    private static void assertJarInArtifactory(String repoKey, String version) throws Exception {
        byte[] jar = downloadArtifact(repoKey, artifactPath(version, "jar"));
        assertTrue(jar.length > 0, "Downloaded jar is empty");
        assertEquals('P', (char) jar[0]);
        assertEquals('K', (char) jar[1]);
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

    private static boolean hasDependencyIdPrefix(JsonNode dependencies, String idPrefix) {
        if (!dependencies.isArray()) {
            return false;
        }
        for (JsonNode dependency : dependencies) {
            if (dependency.path("id").asText().startsWith(idPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
