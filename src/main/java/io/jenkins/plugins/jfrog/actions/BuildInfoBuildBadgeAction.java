package io.jenkins.plugins.jfrog.actions;

import hudson.model.BuildBadgeAction;

/**
 * Represents the build-info URL Action with the Artifactory icon.
 */
public class BuildInfoBuildBadgeAction implements BuildBadgeAction {
    private final String url;

    public BuildInfoBuildBadgeAction(String url) {
        this.url = url;
    }

    public String getIconFileName() {
        return "/plugin/jfrog/icons/artifactory-icon.png";
    }

    public String getDisplayName() {
        return "Artifactory Build Info";
    }

    public String getUrlName() {
        return this.url;
    }
}
