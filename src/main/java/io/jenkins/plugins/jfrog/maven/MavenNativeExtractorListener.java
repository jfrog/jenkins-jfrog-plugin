package io.jenkins.plugins.jfrog.maven;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.maven.MavenArgumentInterceptorAction;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.InvisibleAction;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.ArgumentListBuilder;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs before Jenkins launches the forked Maven process. MavenReporter hooks are too late.
 * Skipped when Maven Integration is not installed ({@code optional = true}).
 */
@Extension(optional = true)
@SuppressWarnings("rawtypes")
public class MavenNativeExtractorListener extends RunListener<AbstractBuild> {

    private static final Logger LOGGER = Logger.getLogger(MavenNativeExtractorListener.class.getName());

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

    static MavenArtifactoryReporter findReporter(AbstractBuild build) {
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
    static void addExtractorLaunchArguments(ArgumentListBuilder args, EnvVars env) {
        if (env == null) {
            return;
        }
        String propsPath = env.get(BuildInfoConfigProperties.ENV_BUILDINFO_PROPFILE);
        if (StringUtils.isBlank(propsPath)) {
            propsPath = env.get(BuildInfoConfigProperties.PROP_PROPS_FILE);
        }
        if (StringUtils.isBlank(propsPath)) {
            return;
        }
        args.add("-D" + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propsPath);
        args.add("-D" + BuildInfoConfigProperties.ACTIVATE_RECORDER + "=true");
    }

    /**
     * Jenkins Maven launches {@code java Maven35Main} and tokenizes job Maven opts on spaces.
     * {@link ArgumentListBuilder#add(String)} keeps {@code -Dkey=value} as one token.
     */
    static final class MavenExtractorArguments extends InvisibleAction implements MavenArgumentInterceptorAction {

        @Override
        public String getGoalsAndOptions(MavenModuleSetBuild build) {
            return null;
        }

        @Override
        public ArgumentListBuilder intercept(ArgumentListBuilder args, MavenModuleSetBuild build) {
            try {
                addExtractorLaunchArguments(args, build.getEnvironment(TaskListener.NULL));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Failed to pass JFrog Maven extractor system properties; environment variables remain the fallback",
                        e);
            }
            return args;
        }
    }
}
