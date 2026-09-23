package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.maven.MavenNativeExtractorListener;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static io.jenkins.plugins.jfrog.JfStep.addBuildInfoActionIfNeeded;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;

/**
 * Post-build action to publish JFrog Build Info.
 * This automatically runs 'jf rt build-publish' after the build completes,
 * publishing collected build information to Artifactory.
 */
public class JfrogBuildInfoPublisher extends Notifier {

    private String jfrogInstallation;
    private boolean publishOnlyOnSuccess = true;

    @DataBoundConstructor
    public JfrogBuildInfoPublisher() {
    }

    public String getJfrogInstallation() {
        return jfrogInstallation;
    }

    @DataBoundSetter
    public void setJfrogInstallation(String jfrogInstallation) {
        this.jfrogInstallation = jfrogInstallation;
    }

    public boolean isPublishOnlyOnSuccess() {
        return publishOnlyOnSuccess;
    }

    @DataBoundSetter
    public void setPublishOnlyOnSuccess(boolean publishOnlyOnSuccess) {
        this.publishOnlyOnSuccess = publishOnlyOnSuccess;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        // Only skip CLI publish when the native Maven reporter is actually configured.
        // Existing Maven jobs that still rely on this publisher (without the new reporter)
        // must keep publishing after upgrade. Also keep running when publishOnlyOnSuccess is
        // explicitly false: that setting means the job wants a publish attempt even on a failed
        // build, a guarantee the native reporter's in-process, goal-driven capture cannot
        // replicate (it has no separate "did the overall build succeed" gate to honor).
        if (isMavenProjectJob(build.getProject().getClass())
                && publishOnlyOnSuccess
                && MavenNativeExtractorListener.findReporter(build) != null) {
            listener.getLogger().println("[JFrog Build Info] Skipping CLI publish for Maven Project jobs. " +
                    "Native Build Settings already publishes build info.");
            return true;
        }

        // Check if we should skip based on build result
        Result buildResult = build.getResult();
        if (publishOnlyOnSuccess && buildResult != null && buildResult.isWorseThan(Result.SUCCESS)) {
            listener.getLogger().println("[JFrog Build Info] Skipping publish - build result is " + buildResult);
            return true;
        }

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            listener.error("[JFrog Build Info] Workspace is null");
            return false;
        }

        EnvVars env = build.getEnvironment(listener);
        
        // Setup JFrog CLI installation environment if specified
        if (StringUtils.isNotBlank(jfrogInstallation)) {
            JfrogInstallation installation = getInstallation();
            if (installation != null) {
                hudson.model.Node node = build.getBuiltOn();
                if (node != null) {
                    installation = installation.forNode(node, listener);
                }
                if (installation != null) {
                    installation = installation.forEnvironment(env);
                }
                if (installation != null) {
                    installation.buildEnvVars(env);
                }
            }
        }

        // Check if JFROG_BINARY_PATH is set (either from installation or Build Environment wrapper)
        if (!env.containsKey(JFROG_BINARY_PATH)) {
            listener.getLogger().println("[JFrog Build Info] Using JFrog CLI from system PATH");
        }

        boolean isWindows = !launcher.isUnix();
        String jfrogBinaryPath = Utils.getJFrogCLIPath(env, isWindows);

        // Setup JFrog environment
        JFrogCliConfigEncryption jfrogCliConfigEncryption = build.getAction(JFrogCliConfigEncryption.class);
        if (jfrogCliConfigEncryption == null) {
            jfrogCliConfigEncryption = new JFrogCliConfigEncryption(env);
            build.addAction(jfrogCliConfigEncryption);
        }

        FilePath jfrogHomeTempDir = Utils.createAndGetJfrogCliHomeTempDir(workspace, String.valueOf(build.getNumber()));
        CliEnvConfigurator.configureCliEnv(env, jfrogHomeTempDir, jfrogCliConfigEncryption);

        // Build the 'jf rt build-publish' command
        ArgumentListBuilder builder = new ArgumentListBuilder();
        builder.add(jfrogBinaryPath).add("rt").add("bp");
        if (isWindows) {
            builder = builder.toWindowsCommand();
        }

        listener.getLogger().println("[JFrog Build Info] Publishing build info...");
        listener.getLogger().println("[JFrog Build Info] Build name: " + env.get("JFROG_CLI_BUILD_NAME"));
        listener.getLogger().println("[JFrog Build Info] Build number: " + env.get("JFROG_CLI_BUILD_NUMBER"));

        try (ByteArrayOutputStream taskOutputStream = new ByteArrayOutputStream()) {
            JfTaskListener jfTaskListener = new JfTaskListener(listener, taskOutputStream);
            Launcher.ProcStarter jfLauncher = launcher.launch()
                    .envs(env)
                    .pwd(workspace)
                    .stdout(jfTaskListener);

            // Configure servers if needed
            if (shouldConfig(jfrogHomeTempDir)) {
                JfStep.Execution.configAllServersForBuilder(
                        jfLauncher, jfrogBinaryPath, isWindows, build.getParent(), false
                );
            }

            // Run 'jf rt bp'
            int exitValue = jfLauncher.cmds(builder).join();
            if (exitValue != 0) {
                listener.error("[JFrog Build Info] Failed to publish build info (exit code: " + exitValue + ")");
                return false;
            }

            // Add build info badge to Jenkins UI
            String[] args = {"rt", "bp"};
            addBuildInfoActionIfNeeded(args, new JenkinsBuildInfoLog(listener), build, taskOutputStream);
            
            listener.getLogger().println("[JFrog Build Info] Build info published successfully");
            return true;
        } catch (Exception e) {
            listener.error("[JFrog Build Info] Error publishing build info: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if servers need to be configured.
     */
    private boolean shouldConfig(FilePath jfrogHomeTempDir) throws IOException, InterruptedException {
        if (jfrogHomeTempDir == null || !jfrogHomeTempDir.exists()) {
            return true;
        }
        return !jfrogHomeTempDir.child("jfrog-cli.conf").exists();
    }

    /**
     * Get the JFrog installation by name.
     */
    private JfrogInstallation getInstallation() {
        if (jfrogInstallation == null) {
            return null;
        }

        JfrogInstallation[] installations = ((DescriptorImpl) getDescriptor()).getInstallations();
        if (installations == null) {
            return null;
        }

        for (JfrogInstallation installation : installations) {
            if (installation != null && jfrogInstallation.equals(installation.getName())) {
                return installation;
            }
        }
        return null;
    }

    @Extension
    @Symbol("jfrogPublishBuildInfo")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Publish JFrog Build Info";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            // Still offered on Maven Project jobs so upgrades that have not yet migrated to
            // MavenArtifactoryReporter can keep publishing via jf rt bp. perform() no-ops only
            // when the native reporter is actually present.
            return true;
        }

        /**
         * Get all configured JFrog CLI installations.
         */
        public JfrogInstallation[] getInstallations() {
            jenkins.model.Jenkins jenkinsInstance = jenkins.model.Jenkins.get();
            return jenkinsInstance.getDescriptorByType(JfrogInstallation.DescriptorImpl.class).getInstallations();
        }

        /**
         * Populate the dropdown list of JFrog CLI installations.
         */
        public ListBoxModel doFillJfrogInstallationItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(Use pre-installed JFrog CLI from system PATH)", "");
            for (JfrogInstallation installation : getInstallations()) {
                items.add(installation.getName(), installation.getName());
            }
            return items;
        }
    }

    /**
     * Used to detect Maven Project jobs. Walks class names so this always-loaded publisher
     * does not initialize Maven Integration. CLI publish is skipped only when a
     * {@code MavenArtifactoryReporter} is also configured on the job.
     */
    static boolean isMavenProjectJob(Class<?> jobType) {
        for (Class<?> type = jobType; type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if ("hudson.maven.MavenModuleSet".equals(name) || "hudson.maven.MavenModule".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
