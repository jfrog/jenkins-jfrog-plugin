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
}
