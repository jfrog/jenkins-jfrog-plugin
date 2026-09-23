package io.jenkins.plugins.jfrog.maven;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenBuildIdentifiersTest {

    @Test
    void resolveBuildNamePrefersReporterFieldAndExpandsVars() {
        EnvVars env = new EnvVars();
        env.put("BRANCH_NAME", "main");
        env.put("JFROG_CLI_BUILD_NAME", "from-env");

        String name = MavenBuildIdentifiers.resolveBuildName("my-build-${BRANCH_NAME}", env, "folder/job");

        assertEquals("my-build-main", name);
    }

    @Test
    void resolveBuildNameFallsBackToJfrogCliEnvThenJobName() {
        EnvVars env = new EnvVars();
        env.put("JFROG_CLI_BUILD_NAME", "from-env");

        assertEquals("from-env", MavenBuildIdentifiers.resolveBuildName("  ", env, "folder/job"));
        // Matches CliEnvConfigurator's raw JOB_NAME default (no separator substitution), so a
        // folder job migrating from the CLI publish path resolves to the same build name.
        assertEquals("folder/job", MavenBuildIdentifiers.resolveBuildName(null, new EnvVars(), "folder/job"));
    }

    @Test
    void resolveBuildNumberPrefersReporterFieldThenEnvThenDefault() {
        EnvVars env = new EnvVars();
        env.put("BUILD_ID", "42");
        env.put("JFROG_CLI_BUILD_NUMBER", "from-env");

        assertEquals("rel-42", MavenBuildIdentifiers.resolveBuildNumber("rel-${BUILD_ID}", env, "7"));
        assertEquals("from-env", MavenBuildIdentifiers.resolveBuildNumber("", env, "7"));
        assertEquals("7", MavenBuildIdentifiers.resolveBuildNumber(null, new EnvVars(), "7"));
    }

    @Test
    void sanitizeBuildNameKeepsSlashesAndHandlesBlank() {
        assertEquals("folder/job", MavenBuildIdentifiers.sanitizeBuildName("folder/job"));
        assertEquals("unknown-build", MavenBuildIdentifiers.sanitizeBuildName("  "));
    }

    @Test
    void resolveBuildUrlPrefersJfrogCliThenJenkins() {
        EnvVars env = new EnvVars();
        env.put("BUILD_URL", "https://jenkins/job/1");
        assertEquals("https://jenkins/job/1", MavenBuildIdentifiers.resolveBuildUrl(env));

        env.put("JFROG_CLI_BUILD_URL", "https://ci/build");
        assertEquals("https://ci/build", MavenBuildIdentifiers.resolveBuildUrl(env));
    }
}
