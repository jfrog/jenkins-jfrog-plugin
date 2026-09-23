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

    /**
     * Passes the properties-file path as one {@code -D} token so workspace paths with spaces
     * survive Jenkins Maven argument splitting. Encryption key/IV stay in the environment
     * only — they must not appear on the {@code Executing Maven:} log line.
     */
    static void addExtractorLaunchArguments(ArgumentListBuilder args, String propsPath) {
        if (StringUtils.isBlank(propsPath)) {
            return;
        }
        args.add("-D" + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propsPath);
        args.add("-D" + BuildInfoConfigProperties.ACTIVATE_RECORDER + "=true");
    }

    /**
     * Jenkins Maven launches {@code java Maven35Main} and tokenizes job Maven opts on spaces.
     * {@link ArgumentListBuilder#add(String)} keeps {@code -Dkey=value} as one token.
     * <p>
     * Holds the properties-file path set directly by {@link MavenNativeExtractorEnvironment}
     * once it's resolved. {@code intercept()} deliberately does NOT call
     * {@code build.getEnvironment()} to re-derive this - that call re-invokes every registered
     * {@code Environment.buildEnvVars()} (including the one that populates this very path), which
     * both duplicates a full environment resolution on every build and, worse, would silently
     * swallow a configuration error {@code buildEnvVars()} is meant to fail the build on, since
     * the re-invocation happens deep inside this class's own try/catch rather than Jenkins core's
     * build-setup path.
     */
    static final class MavenExtractorArguments extends InvisibleAction implements MavenArgumentInterceptorAction {

        private volatile String propsPath;

        void setPropsPath(String propsPath) {
            this.propsPath = propsPath;
        }

        @Override
        public String getGoalsAndOptions(MavenModuleSetBuild build) {
            return null;
        }

        @Override
        public ArgumentListBuilder intercept(ArgumentListBuilder args, MavenModuleSetBuild build) {
            addExtractorLaunchArguments(args, propsPath);
            return args;
        }
    }
}
