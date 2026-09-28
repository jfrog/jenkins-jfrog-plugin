package io.jenkins.plugins.jfrog.maven;

import hudson.Extension;
import hudson.Launcher;
import hudson.maven.MavenArgumentInterceptorAction;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.InvisibleAction;
import hudson.model.listeners.RunListener;
import hudson.util.ArgumentListBuilder;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;

import java.io.IOException;

/**
 * Runs before Jenkins launches the forked Maven process. MavenReporter hooks are too late.
 * Skipped when Maven Integration is not installed ({@code optional = true}).
 */
@Extension(optional = true)
@SuppressWarnings("rawtypes")
public class MavenNativeExtractorListener extends RunListener<AbstractBuild> {

    @Override
    public Environment setUpEnvironment(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        MavenArtifactoryReporter reporter = findReporter(build);
        if (reporter == null) {
            return new Environment() {
            };
        }
        if (build.getAction(MavenExtractorArguments.class) == null) {
            build.addAction(new MavenExtractorArguments());
        }
        return new MavenNativeExtractorEnvironment(build, reporter, listener);
    }

    public static MavenArtifactoryReporter findReporter(AbstractBuild build) {
        if (!(build instanceof MavenModuleSetBuild)) {
            return null;
        }
        return ((MavenModuleSetBuild) build).getProject().getReporters().get(MavenArtifactoryReporter.class);
    }

    static void addExtractorLaunchArguments(ArgumentListBuilder args, String propsPath, boolean activateRecorder) {
        if (StringUtils.isBlank(propsPath)) {
            return;
        }
        // One -D token so workspace paths with spaces survive Maven argument splitting.
        args.add("-D" + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propsPath);
        if (activateRecorder) {
            args.add("-D" + BuildInfoConfigProperties.ACTIVATE_RECORDER + "=true");
        }
    }

    /**
     * Holds the properties-file path set by {@link MavenNativeExtractorEnvironment}.
     * {@code intercept()} must not call {@code build.getEnvironment()} — that re-invokes
     * {@code buildEnvVars()} and can swallow a configuration error as a logged exception.
     */
    static final class MavenExtractorArguments extends InvisibleAction implements MavenArgumentInterceptorAction {

        private volatile String propsPath;
        private volatile boolean activateRecorder;

        void setPropsPath(String propsPath) {
            this.propsPath = propsPath;
        }

        void setActivateRecorder(boolean activateRecorder) {
            this.activateRecorder = activateRecorder;
        }

        @Override
        public String getGoalsAndOptions(MavenModuleSetBuild build) {
            return null;
        }

        @Override
        public ArgumentListBuilder intercept(ArgumentListBuilder args, MavenModuleSetBuild build) {
            addExtractorLaunchArguments(args, propsPath, activateRecorder);
            return args;
        }
    }
}
