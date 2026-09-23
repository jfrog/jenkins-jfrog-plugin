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
        for (String token : goals.split("\\s+")) {
            // Skip CLI options (e.g. -Dmaven.deploy.skip=true, -Pdeploy-profile) - only match an
            // actual lifecycle phase or plugin goal, not text that happens to contain "install"
            // or "deploy" as a substring of an option name or value.
            if (token.startsWith("-")) {
                continue;
            }
            // Strip a Maven "@executionId" suffix (e.g. "...maven-install-plugin:install@my-exec"),
            // the standard syntax for binding a goal to a specific execution id.
            int at = token.indexOf('@');
            String goal = at >= 0 ? token.substring(0, at) : token;
            if (goal.equals("install") || goal.equals("deploy")
                    || goal.endsWith(":install") || goal.endsWith(":deploy")) {
                return true;
            }
        }
        return false;
    }
}
