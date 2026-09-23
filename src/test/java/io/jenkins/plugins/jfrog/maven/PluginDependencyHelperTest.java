package io.jenkins.plugins.jfrog.maven;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDependencyHelperTest {

    @Test
    void cacheVersionUsesUnknownWhenPluginIsMissing() {
        assertEquals("unknown", PluginDependencyHelper.cacheVersion(null, 123L));
    }

    @Test
    void cacheVersionKeepsReleaseVersions() {
        assertEquals("2.0.0", PluginDependencyHelper.cacheVersion("2.0.0", 123L));
    }

    @Test
    void cacheVersionAddsTimestampForSnapshots() {
        String version = PluginDependencyHelper.cacheVersion("2.0.x-SNAPSHOT (private-123)", 99L);

        assertTrue(version.startsWith("2.0.x-SNAPSHOT-"));
        assertTrue(version.endsWith("-99"));
    }

    @Test
    void shouldSkipJenkinsPluginJar() {
        assertTrue(PluginDependencyHelper.shouldSkipJenkinsPluginJar("classes.jar"));
        assertTrue(PluginDependencyHelper.shouldSkipJenkinsPluginJar("jfrog.jar"));
        assertTrue(PluginDependencyHelper.shouldSkipJenkinsPluginJar("build-info-extractor-maven3-2.41.23.jar"));
    }

    @Test
    void readExtractorJarNamesReturnsOnlyJars() throws Exception {
        java.util.List<String> names = PluginDependencyHelper.readExtractorJarNames(
                PluginDependencyHelper.class.getClassLoader());

        assertFalse(names.isEmpty());
        assertTrue(names.stream().allMatch(name -> name.endsWith(".jar")));
        assertTrue(names.stream().anyMatch(name -> name.startsWith("build-info-extractor-maven3-")));
    }

    @Test
    void stagedExtractorJarContainsResolverClasses() throws Exception {
        java.util.List<String> names = PluginDependencyHelper.readExtractorJarNames(
                PluginDependencyHelper.class.getClassLoader());
        String extractor = names.stream()
                .filter(name -> name.startsWith("build-info-extractor-maven3-") && !name.endsWith("-no-resolver.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No full build-info-extractor-maven3 jar in jars.list: " + names));

        String resource = PluginDependencyHelper.EXTRACTOR_LIB_RESOURCE + "/" + extractor;
        try (InputStream in = PluginDependencyHelper.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "Missing classpath resource " + resource);
            boolean found = false;
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseArtifactResolver.class"
                            .equals(entry.getName())) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found, "Staged extractor jar must keep ArtifactoryEclipseArtifactResolver for Resolve Repository");
        }
    }

    @Test
    void stagedNoResolverJarExcludesResolverClasses() throws Exception {
        java.util.List<String> names = PluginDependencyHelper.readExtractorJarNames(
                PluginDependencyHelper.class.getClassLoader());
        String extractor = names.stream()
                .filter(name -> name.endsWith("-no-resolver.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No no-resolver jar in jars.list: " + names));

        String resource = PluginDependencyHelper.EXTRACTOR_LIB_RESOURCE + "/" + extractor;
        try (InputStream in = PluginDependencyHelper.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "Missing classpath resource " + resource);
            boolean found = false;
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseArtifactResolver.class"
                            .equals(entry.getName())) {
                        found = true;
                        break;
                    }
                }
            }
            assertFalse(found, "No-resolver jar must not carry the Artifactory resolver overrides "
                    + "(they NPE on Maven 3.9.12+ as soon as they are on the classpath - jfrog/build-info#841)");
        }
    }
}
