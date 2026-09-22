package io.jenkins.plugins.jfrog.maven;

import org.junit.jupiter.api.Test;

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
}
