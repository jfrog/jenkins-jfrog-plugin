package io.jenkins.plugins.jfrog.maven;

import hudson.model.Action;

/**
 * Renders a build-info summary box on a Maven-native build's own page (via
 * {@code summary.jelly}) only - unlike {@link io.jenkins.plugins.jfrog.actions.BuildInfoBuildBadgeAction},
 * which is a {@code BuildBadgeAction} and therefore also renders in build-history rows, the
 * project page, etc. Returning {@code null} from the {@code Action} methods below keeps this out
 * of the sidebar and out of anywhere else Jenkins lists a build's actions - only the summary box
 * shows up.
 */
public class MavenBuildInfoAction implements Action {

    private final String buildInfoUrl;

    public MavenBuildInfoAction(String buildInfoUrl) {
        this.buildInfoUrl = buildInfoUrl;
    }

    public String getBuildInfoUrl() {
        return buildInfoUrl;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
