package io.jenkins.plugins.jfrog.maven;

import org.apache.commons.lang3.StringUtils;

/**
 * build-info-extractor-maven3 publishes only when Maven goals include install or deploy.
 */
final class MavenGoals {

    private MavenGoals() {
    }

    static boolean allowsArtifactoryPublish(String goals) {
        if (StringUtils.isBlank(goals)) {
            return false;
        }
        return goals.contains("install") || goals.contains("deploy");
    }
}
