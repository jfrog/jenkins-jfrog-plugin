package io.jenkins.plugins.jfrog.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenGoalsTest {

    @Test
    void allowsPublishWhenGoalsIncludeInstallOrDeploy() {
        assertTrue(MavenGoals.allowsArtifactoryPublish("clean install"));
        assertTrue(MavenGoals.allowsArtifactoryPublish("-B -DskipTests deploy"));
        assertTrue(MavenGoals.allowsArtifactoryPublish("install:install"));
    }

    @Test
    void rejectsGoalsThatExtractorWouldSkip() {
        assertFalse(MavenGoals.allowsArtifactoryPublish(null));
        assertFalse(MavenGoals.allowsArtifactoryPublish(""));
        assertFalse(MavenGoals.allowsArtifactoryPublish("clean package"));
        assertFalse(MavenGoals.allowsArtifactoryPublish("clean verify"));
    }

    @Test
    void doesNotFalsePositiveOnOptionsThatContainInstallOrDeployAsASubstring() {
        // A common pattern for intentionally skipping deploy - "deploy" here is inside a system
        // property name/value, not an actual lifecycle phase or plugin goal.
        assertFalse(MavenGoals.allowsArtifactoryPublish("clean package -Dmaven.deploy.skip=true"));
        assertFalse(MavenGoals.allowsArtifactoryPublish("clean package -Dmaven.install.skip=true"));
        assertFalse(MavenGoals.allowsArtifactoryPublish("clean package -Pdeploy-profile"));
    }

    @Test
    void allowsPluginGoalShorthandForInstallOrDeploy() {
        assertTrue(MavenGoals.allowsArtifactoryPublish("clean deploy:deploy"));
        assertTrue(MavenGoals.allowsArtifactoryPublish("org.apache.maven.plugins:maven-install-plugin:install"));
    }

    @Test
    void allowsGoalBoundToASpecificExecutionId() {
        // Standard Maven syntax for binding a goal to a specific <execution> id.
        assertTrue(MavenGoals.allowsArtifactoryPublish(
                "org.apache.maven.plugins:maven-install-plugin:install@my-exec"));
        assertTrue(MavenGoals.allowsArtifactoryPublish("deploy:deploy@my-exec"));
    }
}
