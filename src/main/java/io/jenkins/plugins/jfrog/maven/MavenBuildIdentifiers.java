package io.jenkins.plugins.jfrog.maven;

import hudson.EnvVars;
import org.apache.commons.lang3.StringUtils;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.JFROG_CLI_BUILD_NAME;
import static io.jenkins.plugins.jfrog.CliEnvConfigurator.JFROG_CLI_BUILD_NUMBER;
import static io.jenkins.plugins.jfrog.CliEnvConfigurator.JFROG_CLI_BUILD_URL;

/**
 * Build name/number for Maven Project jobs. Same env-var names as {@code CliEnvConfigurator}.
 */
final class MavenBuildIdentifiers {

    private MavenBuildIdentifiers() {
    }

    static String resolveBuildName(String configuredName, EnvVars env, String jobFullName) {
        if (StringUtils.isNotBlank(configuredName)) {
            return sanitizeBuildName(env.expand(configuredName));
        }
        String override = env.get(JFROG_CLI_BUILD_NAME);
        if (StringUtils.isNotBlank(override)) {
            return sanitizeBuildName(override);
        }
        return sanitizeBuildName(jobFullName);
    }

    static String resolveBuildNumber(String configuredNumber, EnvVars env, String defaultNumber) {
        if (StringUtils.isNotBlank(configuredNumber)) {
            return env.expand(configuredNumber);
        }
        String override = env.get(JFROG_CLI_BUILD_NUMBER);
        if (StringUtils.isNotBlank(override)) {
            return override;
        }
        return defaultNumber;
    }

    static String resolveBuildUrl(EnvVars env) {
        return env.get(JFROG_CLI_BUILD_URL, env.get("BUILD_URL"));
    }

    static String sanitizeBuildName(String jobName) {
        // Keep '/' as-is for folder jobs (e.g. "myfolder/myapp") - CliEnvConfigurator uses the
        // raw JOB_NAME env var for the same default, with no separator substitution. Migrating a
        // folder job from the CLI publish path to this reporter must resolve to the same build
        // name, or it silently starts a disconnected build-info history in Artifactory.
        if (StringUtils.isBlank(jobName)) {
            return "unknown-build";
        }
        return jobName;
    }
}
